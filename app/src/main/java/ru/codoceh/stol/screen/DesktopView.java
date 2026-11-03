package ru.codoceh.stol.screen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private boolean isScaling;
    private float lastCentreX;
    private float lastCentreY;

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
                        viewport.setZoom(viewport.zoom() * detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        framebuffer.frameComplete();
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

    /** Сколько точек вида от нижнего края занимает полоса высотой {@code framePx} внизу стола. */
    public int bottomBandHeight(int framePx) {
        if (!viewport.isReady() || getHeight() <= 0) {
            return framePx;
        }
        float scale = viewport.baseScale();
        float frameBottom = (getHeight() + viewport.frameHeight() * scale) / 2f;
        return Math.round(getHeight() - frameBottom + framePx * scale);
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
    private volatile android.net.Uri lastReceivedClipImageUri;

    /** Подключиться к столу на петле и начать показ. Повторный вызов ничего не делает. */
    public void start(int port) {
        start(port, 0);
    }

    /**
     * То же самое, и сверх этого — наименьший промежуток между кадрами в миллисекундах
     * (см. {@link RfbClient#setMinFrameIntervalMs}); {@code 0} — без ограничения.
     */
    public void start(int port, long minFrameIntervalMs) {
        if (network != null) {
            return;
        }
        final RfbClient starting = new RfbClient(port, framebuffer);
        starting.setMinFrameIntervalMs(minFrameIntervalMs);
        client = starting;
        sender = Executors.newSingleThreadExecutor();

        clipboard = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipListener = () -> {
                android.content.ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    android.content.ClipData.Item item = clip.getItemAt(0);
                    CharSequence cs = item.getText();
                    if (cs != null && cs.length() > 0) {
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
                    } else {
                        android.net.Uri uri = item.getUri();
                        // Сравнение с lastReceivedClipImageUri обрывает пинг-понг: без него
                        // картинка, которую мы сами только что положили из onExtendedClipboardImage
                        // (R09.1), отражалась бы этим же слушателем обратно на стол.
                        if (uri != null && !uri.equals(lastReceivedClipImageUri)) {
                            sendClipboardImage(starting, uri);
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

        starting.setExtendedClipboardImageListener(this::receiveClipboardImage);

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

    private static final String TAG = "DesktopView";

    /**
     * Картиночная ветка буфера обмена (Android → стол, interfaces.md, spec.md R02.1/R08.1).
     * Декодирует {@code uri} в {@link Bitmap} и отправляет как DIB через {@link RfbClient},
     * если собеседник вообще согласился принимать картинки — иначе не тратит время на
     * декодирование впустую.
     */
    private void sendClipboardImage(RfbClient starting, android.net.Uri uri) {
        if (!starting.isExtendedClipboardImageSupported()) {
            return;
        }
        android.content.ContentResolver resolver = getContext().getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null || !mime.startsWith("image/")) {
            return;
        }
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return;
            }
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException failure) {
            Log.w(TAG, "буфер обмена: не удалось прочитать картинку из " + uri, failure);
            return;
        } catch (OutOfMemoryError failure) {
            Log.w(TAG, "буфер обмена: картинка из " + uri + " не влезла в память", failure);
            return;
        }
        if (bitmap == null) {
            Log.w(TAG, "буфер обмена: " + uri + " не распознан как картинка");
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] dib = DibCodec.argbToDib(pixels, width, height);

        ExecutorService exec = sender;
        if (exec != null && !exec.isShutdown()) {
            exec.execute(() -> {
                try {
                    starting.sendExtendedClipboardImage(dib);
                } catch (IOException failure) {
                    Log.w(TAG, "буфер обмена: не удалось отправить картинку", failure);
                }
            });
        }
    }

    /**
     * Картиночная ветка буфера обмена (стол → Android, interfaces.md, spec.md R02.2/R09.1).
     * Вызывается из потока сети, когда {@link RfbClient} успешно распаковал и провалидировал
     * заголовок DIB. {@link DibCodec#dibToArgb} может дополнительно отбраковать содержимое,
     * прошедшее ту минимальную проверку (например неподдерживаемый {@code biBitCount}) —
     * такой случай, как и сбой записи файла, отбрасывается с логом, без падения приложения
     * и без разрыва RFB-соединения (spec.md R02.2 «Кривой ввод / отказ», «Прерывание»).
     */
    private void receiveClipboardImage(byte[] dibBytes) {
        int[] outWidthHeight = new int[2];
        int[] pixels;
        try {
            pixels = DibCodec.dibToArgb(dibBytes, outWidthHeight);
        } catch (IllegalArgumentException failure) {
            Log.w(TAG, "буфер обмена: битый DIB со стола", failure);
            return;
        }
        int width = outWidthHeight[0];
        int height = outWidthHeight[1];
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);

        File dir = new File(getContext().getCacheDir(), "clipboard");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "буфер обмена: не удалось создать каталог " + dir);
            return;
        }
        File file = new File(dir, "clipboard_image.png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException failure) {
            Log.w(TAG, "буфер обмена: не удалось сохранить картинку со стола", failure);
            return;
        }

        android.net.Uri uri = FileProvider.getUriForFile(
                getContext(), getContext().getPackageName() + ".fileprovider", file);
        post(() -> {
            android.content.ClipboardManager current = clipboard;
            if (current != null) {
                lastReceivedClipImageUri = uri;
                android.content.ClipData clipData = android.content.ClipData.newUri(
                        getContext().getContentResolver(), "image", uri);
                current.setPrimaryClip(clipData);
            }
        });
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
        int action = event.getActionMasked();

        if (pointerCount == 2) {
            scaleDetector.onTouchEvent(event);
        } else {
            isScaling = false;
        }

        if (pointerCount >= 2) {
            float cx = centre(event, true);
            float cy = centre(event, false);
            // Зум приближает картинку стола; два пальца, сдвинутые при зуме, двигают кадр.
            if (pointerCount == 2 && action == MotionEvent.ACTION_MOVE && viewport.zoom() > 1f) {
                viewport.pan(cx - lastCentreX, cy - lastCentreY);
                framebuffer.frameComplete();
            }
            lastCentreX = cx;
            lastCentreY = cy;

            if (pointerCount == 2 && (scaleDetector.isInProgress() || isScaling)) {
                touch.cancel();
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                if (pointerCount >= 3) {
                    touch.threeFinger(cx, cy);
                } else {
                    touch.secondFinger(cx, cy);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (viewport.zoom() <= 1f) {
                    touch.move(cx, cy);
                }
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

    /** Нажать или отпустить клавишу на столе — для экранного геймпада. */
    public void sendKey(int keysym, boolean down) {
        key(keysym, down);
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
