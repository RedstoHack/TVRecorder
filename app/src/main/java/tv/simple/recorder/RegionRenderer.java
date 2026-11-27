package tv.simple.recorder;

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Вырезает прямоугольную зону из кадров экрана и рисует её в поверхность кодировщика.
 * <p>
 * Поток кадров: VirtualDisplay → SurfaceTexture (этот класс) → OpenGL → Surface кодировщика.
 */
final class RegionRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "RegionRenderer";

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n"
                    + "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                    + "}\n";

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private final Surface encoderSurface;
    private final int outWidth;
    private final int outHeight;
    private final int srcWidth;
    private final int srcHeight;
    private final RectF zone;

    private HandlerThread thread;
    private Handler handler;
    private volatile Throwable initError;
    private volatile boolean released;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program;
    private int textureId;
    private int aPosition;
    private int aTexCoord;
    private int uTexMatrix;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texBuffer;
    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private final float[] texMatrix = new float[16];

    /**
     * @param encoderSurface поверхность кодировщика, куда рисуем вырезанную зону
     * @param outWidth       ширина видео
     * @param outHeight      высота видео
     * @param srcWidth       ширина экрана, который снимает VirtualDisplay
     * @param srcHeight      высота экрана
     * @param zone           зона в долях экрана (0..1), начало координат слева сверху
     */
    RegionRenderer(Surface encoderSurface, int outWidth, int outHeight,
                   int srcWidth, int srcHeight, RectF zone) {
        this.encoderSurface = encoderSurface;
        this.outWidth = outWidth;
        this.outHeight = outHeight;
        this.srcWidth = srcWidth;
        this.srcHeight = srcHeight;
        this.zone = new RectF(zone);
    }

    /** Запускает поток OpenGL и возвращает поверхность, на которую нужно снимать экран. */
    Surface start() throws IOException, InterruptedException {
        thread = new HandlerThread("region-renderer");
        thread.start();
        handler = new Handler(thread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                init();
            } catch (Throwable t) {
                initError = t;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            release();
            throw new IOException("OpenGL init timed out");
        }
        if (initError != null) {
            Throwable cause = initError;
            release();
            throw new IOException("OpenGL init failed: " + cause.getMessage(), cause);
        }
        return inputSurface;
    }

    /** Останавливает отрисовку и освобождает OpenGL. Можно вызывать повторно. */
    void release() {
        if (thread == null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            handler.post(() -> {
                try {
                    released = true;
                    teardown();
                } finally {
                    latch.countDown();
                }
            });
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "release failed", e);
        }
        thread.quitSafely();
        thread = null;
        handler = null;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // Вызывается уже в потоке OpenGL: слушатель зарегистрирован с нашим Handler.
        if (released) {
            return;
        }
        try {
            drawFrame();
        } catch (RuntimeException e) {
            Log.e(TAG, "drawFrame failed", e);
        }
    }

    // ---------------- Всё ниже выполняется в потоке OpenGL ----------------

    private void init() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("No EGL display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new RuntimeException("No suitable EGL config");
        }

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("eglCreateContext failed");
        }

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface,
                surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreateWindowSurface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");

        // Координаты текстуры: начало слева снизу, поэтому по вертикали зона переворачивается.
        float sMin = zone.left;
        float sMax = zone.right;
        float tMin = 1f - zone.bottom;
        float tMax = 1f - zone.top;
        float[] vertices = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        float[] texCoords = {sMin, tMin, sMax, tMin, sMin, tMax, sMax, tMax};
        vertexBuffer = toBuffer(vertices);
        texBuffer = toBuffer(texCoords);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(srcWidth, srcHeight);
        surfaceTexture.setOnFrameAvailableListener(this, handler);
        inputSurface = new Surface(surfaceTexture);
    }

    private void drawFrame() {
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(texMatrix);
        long timestampNs = surfaceTexture.getTimestamp();

        GLES20.glViewport(0, 0, outWidth, outHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, texBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private void teardown() {
        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private static FloatBuffer toBuffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(data);
        buffer.position(0);
        return buffer;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }
}
