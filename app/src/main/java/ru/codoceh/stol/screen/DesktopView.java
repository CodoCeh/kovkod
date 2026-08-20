package ru.codoceh.stol.screen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Стол на экране телефона: показывает кадр от {@link RfbClient} и отправляет ему ввод.
 *
 * <p>Тонкая прослойка между Android и своими классами: касания разбирает
 * {@link TouchTranslator}, клавиши переводит {@link Keysyms}, размер считает
 * {@link Viewport} — здесь остаются только вызовы системы, которые без устройства не
 * проверить.
 *
 * <p>Рисуем в потоке сети: кадр разобран — кадр показан, без передачи через главный поток.
 * Ввод, наоборот, уходит с отдельного потока, чтобы запись в сокет не висела на главном.
 */
public class DesktopView extends SurfaceView implements SurfaceHolder.Callback,
        Framebuffer.FrameListener {

    /** Что происходит со связью. Вызывается из потока сети. */
    public interface Listener {
        void onConnected(String desktopName, int width, int height);

        void onDisconnected(IOException failure);
    }

    /** Через столько точек экрана ведение двумя пальцами даёт щелчок колесом. */
    private static final int WHEEL_STEP_DP = 32;

    private final Framebuffer framebuffer = new Framebuffer();
    private final Viewport viewport = new Viewport();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TouchTranslator touch;
    private final android.view.ScaleGestureDetector scaleDetector;

    private ExecutorService sender;
    private Thread network;
    private RfbClient client;
    private Listener listener;

    private Bitmap frame;
    private int[] snapshot = new int[0];
    private volatile boolean surfaceReady;

    private float lastTwoFingerX;
    private float lastTwoFingerY;
    private boolean isScaling;

    public DesktopView(Context context) {
        this(context, null);
    }

    public DesktopView(Context context, AttributeSet attributes) {
        super(context, attributes);
        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        int wheelStep = Math.round(WHEEL_STEP_DP * getResources().getDisplayMetrics().density);
        touch = new TouchTranslator(viewport, new Mouse(), slop, wheelStep);
        scaleDetector = new android.view.ScaleGestureDetector(context,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                        isScaling = true;
                        touch.cancel();
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        viewport.setZoom(viewport.zoom() * factor, detector.getFocusX(), detector.getFocusY());
                        redrawSurface();
                        notifyZoom();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                        isScaling = false;
                    }
                });
        framebuffer.setFrameListener(this);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
    }

    public interface ZoomListener {
        void onZoomChanged(float zoom);
    }

    private ZoomListener zoomListener;

    public void setZoomListener(ZoomListener zoomListener) {
        this.zoomListener = zoomListener;
    }

    private void notifyZoom() {
        ZoomListener zl = zoomListener;
        if (zl != null) {
            post(() -> zl.onZoomChanged(viewport.zoom()));
        }
    }

    public void resetZoom() {
        viewport.resetZoom();
        redrawSurface();
        notifyZoom();
    }

    public void toggleZoom() {
        if (viewport.zoom() > 1.05f) {
            resetZoom();
        } else {
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            viewport.setZoom(2.0f, cx, cy);
            redrawSurface();
            notifyZoom();
        }
    }

    public float getZoom() {
        return viewport.zoom();
    }

    public void redrawSurface() {
        if (!surfaceReady || frame == null) {
            return;
        }
        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(Color.BLACK);
            float scale = viewport.scale();
            matrix.setScale(scale, scale);
            matrix.postTranslate(viewport.offsetX(), viewport.offsetY());
            canvas.drawBitmap(frame, matrix, paint);
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    public interface ResizeListener {
        void onResize(int width, int height);
    }

    private ResizeListener resizeListener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setResizeListener(ResizeListener resizeListener) {
        this.resizeListener = resizeListener;
    }

    public void setTouchMode(TouchTranslator.Mode mode) {
        touch.setMode(mode);
    }

    public TouchTranslator.Mode getTouchMode() {
        return touch.mode();
    }

    private android.content.ClipboardManager clipboard;
    private android.content.ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private volatile String lastSentClip;
    private volatile String lastReceivedClip;

    /** Подключиться к столу на петле и начать показ. Повторный вызов ничего не делает. */
    public void start(int port) {
        if (network != null) {
            return;
        }
        final RfbClient starting = new RfbClient(port, framebuffer);
        client = starting;
        sender = Executors.newSingleThreadExecutor();

        clipboard = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipListener = () -> {
                android.content.ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence cs = clip.getItemAt(0).getText();
                    if (cs != null) {
                        String text = cs.toString();
                        if (!text.equals(lastReceivedClip) && !text.equals(lastSentClip)) {
                            lastSentClip = text;
                            ExecutorService exec = sender;
                            if (exec != null && !exec.isShutdown()) {
                                exec.execute(() -> {
                                    try {
                                        starting.sendCutText(text);
                                    } catch (IOException ignored) {
                                    }
                                });
                            }
                        }
                    }
                }
            };
            clipboard.addPrimaryClipChangedListener(clipListener);
        }

        starting.setCutTextListener(text -> {
            if (text != null && !text.equals(lastSentClip) && !text.equals(lastReceivedClip)) {
                lastReceivedClip = text;
                post(() -> {
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("stol", text));
                    }
                });
            }
        });

        network = new Thread(() -> {
            try {
                starting.connect();
                Listener current = listener;
                if (current != null) {
                    current.onConnected(starting.desktopName(), starting.width(), starting.height());
                }
                starting.pump();
            } catch (IOException failure) {
                report(failure);
            } catch (RuntimeException failure) {
                report(new IOException("кадр от сервера не разобрать", failure));
            }
        }, "stol-screen");
        network.start();
    }

    /** Разорвать связь и освободить поток. */
    public void stop() {
        if (clipboard != null && clipListener != null) {
            clipboard.removePrimaryClipChangedListener(clipListener);
            clipListener = null;
        }
        RfbClient open = client;
        client = null;
        if (open != null) {
            open.close();
        }
        if (sender != null) {
            sender.shutdownNow();
            sender = null;
        }
        network = null;
    }

    // --- показ кадра -----------------------------------------------------------------

    @Override
    public void onFrame(Framebuffer source) {
        if (!surfaceReady) {
            return;
        }
        int width = source.width();
        int height = source.height();
        if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            snapshot = new int[width * height];
            viewport.setFrameSize(width, height);
        }
        if (source.snapshot(snapshot) == 0) {
            return;
        }
        frame.setPixels(snapshot, 0, width, 0, 0, width, height);

        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(Color.BLACK);
            float scale = viewport.scale();
            matrix.setScale(scale, scale);
            matrix.postTranslate(viewport.offsetX(), viewport.offsetY());
            canvas.drawBitmap(frame, matrix, paint);
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Сюда приходят и поворот, и складывание, и вылезшая клавиатура: стол просто
        // вписывается в новый размер окна, поэтому обрезанным он не остаётся.
        viewport.setViewSize(width, height);
        framebuffer.frameComplete();
        ResizeListener rl = resizeListener;
        if (rl != null && width > 0 && height > 0) {
            rl.onResize(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // --- мышь из касаний -------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();

        if (pointerCount == 2) {
            scaleDetector.onTouchEvent(event);
        } else {
            isScaling = false;
        }

        if (pointerCount == 2 && (scaleDetector.isInProgress() || isScaling)) {
            touch.cancel();
            return true;
        }

        if (pointerCount >= 2) {
            float cx = centre(event, true);
            float cy = centre(event, false);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                lastTwoFingerX = cx;
                lastTwoFingerY = cy;
                if (pointerCount >= 3) {
                    touch.threeFinger(cx, cy);
                } else {
                    touch.secondFinger(cx, cy);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = cx - lastTwoFingerX;
                float dy = cy - lastTwoFingerY;
                lastTwoFingerX = cx;
                lastTwoFingerY = cy;

                if (pointerCount == 2 && viewport.zoom() > 1.05f && (Math.abs(dx) > 1f || Math.abs(dy) > 1f)) {
                    viewport.pan(dx, dy);
                    redrawSurface();
                    return true;
                }
                touch.move(cx, cy);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                return true;
            }
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocusFromTouch();
                touch.down(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                touch.secondFinger(centre(event, true), centre(event, false));
                return true;

            case MotionEvent.ACTION_MOVE:
                touch.move(centre(event, true), centre(event, false));
                return true;

            case MotionEvent.ACTION_UP:
                if (event.getEventTime() - event.getDownTime()
                        >= ViewConfiguration.getLongPressTimeout()) {
                    touch.longPress();
                }
                touch.up();
                return true;

            case MotionEvent.ACTION_CANCEL:
                touch.cancel();
                return true;

            default:
                return true;
        }
    }

    /** Середина между пальцами; для одного пальца — он сам. */
    private static float centre(MotionEvent event, boolean horizontal) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += horizontal ? event.getX(i) : event.getY(i);
        }
        return sum / event.getPointerCount();
    }

    private final class Mouse implements TouchTranslator.Mouse {
        @Override
        public void move(final int x, final int y, final int buttons) {
            send(desktop -> desktop.sendPointer(x, y, buttons));
        }

        @Override
        public void click(final int x, final int y, final int button) {
            send(desktop -> {
                desktop.sendPointer(x, y, button);
                desktop.sendPointer(x, y, 0);
            });
        }

        @Override
        public void wheel(final int x, final int y, final boolean up) {
            send(desktop -> desktop.sendWheel(x, y, up));
        }
    }

    // --- клавиатура ------------------------------------------------------------------

    /** Вызвать экранную клавиатуру. Окно само подстроится — см. {@link #surfaceChanged}. */
    public void showKeyboard() {
        requestFocus();
        InputMethodManager manager = keyboards();
        if (manager != null) {
            manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void hideKeyboard() {
        InputMethodManager manager = keyboards();
        if (manager != null) {
            manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    private InputMethodManager keyboards() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        // TYPE_NULL: просим клавиатуру слать нажатия клавиш, а не готовый текст.
        info.inputType = InputType.TYPE_NULL;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new DesktopInput();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int keysym = keysym(keyCode, event);
        if (keysym == Keysyms.NONE) {
            return super.onKeyDown(keyCode, event);
        }
        key(keysym, true);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int keysym = keysym(keyCode, event);
        if (keysym == Keysyms.NONE) {
            return super.onKeyUp(keyCode, event);
        }
        key(keysym, false);
        return true;
    }

    private int keysym(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return Keysyms.NONE;
        }
        int special = Keysyms.forKeyCode(keyCode);
        if (special != Keysyms.NONE) {
            return special;
        }
        // Ctrl и Alt уходят отдельными клавишами, поэтому символ берём без них:
        // иначе Ctrl+C дал бы пустой символ вместо «c».
        int meta = event.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK);
        return Keysyms.forCharacter(event.getUnicodeChar(meta));
    }

    private void key(final int keysym, final boolean down) {
        send(desktop -> desktop.sendKey(keysym, down));
    }

    private void type(CharSequence text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            i += Character.charCount(codePoint);
            tap(Keysyms.forCharacter(codePoint));
        }
    }

    private void tap(final int keysym) {
        if (keysym == Keysyms.NONE) {
            return;
        }
        send(desktop -> {
            desktop.sendKey(keysym, true);
            desktop.sendKey(keysym, false);
        });
    }

    /** Клавиатуры, которые всё-таки шлют текст, тоже превращаем в нажатия клавиш. */
    private final class DesktopInput extends BaseInputConnection {

        DesktopInput() {
            super(DesktopView.this, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            type(text);
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) {
                tap(Keysyms.BACKSPACE);
            }
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return dispatchKeyEvent(event);
        }
    }

    // --- отправка --------------------------------------------------------------------

    private interface Message {
        void writeTo(RfbClient client) throws IOException;
    }

    /** Клиент передаётся в очередь, а не берётся из поля: {@link #stop()} может обнулить его. */
    private void send(final Message message) {
        final ExecutorService queue = sender;
        final RfbClient open = client;
        if (queue == null || open == null || queue.isShutdown()) {
            return;
        }
        queue.execute(() -> {
            try {
                message.writeTo(open);
            } catch (IOException failure) {
                report(failure);
            }
        });
    }

    private void report(IOException failure) {
        Listener current = listener;
        if (current != null && client != null) {
            current.onDisconnected(failure);
        }
    }
}
