package ru.codoceh.stol.screen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент RFB против настоящего сокета на петле: рукопожатие, разбор кадра и события ввода
 * проверяются байтами, а не моками, — иначе перепутанный порядок байтов прошёл бы мимо.
 */
public class ScreenRfbClientTest {

    private FakeRfbServer server;
    private Framebuffer framebuffer;
    private RfbClient client;

    @Before
    public void setUp() throws IOException {
        server = new FakeRfbServer();
        framebuffer = new Framebuffer();
        client = new RfbClient(server.port(), framebuffer);
    }

    @After
    public void tearDown() {
        client.close();
        server.close();
    }

    private void connect() throws IOException {
        server.start();
        client.connect();
    }

    // --- рукопожатие -----------------------------------------------------------------

    @Test
    public void speaksRfb38AndTakesTheOnlySecurityWorthHavingOnLoopback() throws IOException {
        connect();
        server.awaitMessages(3);

        assertEquals("RFB 003.008\n", server.clientVersion());
        assertEquals("тип безопасности не None", 1, server.chosenSecurity());
        assertEquals("стол просим общим", 1, server.sharedFlag());
    }

    @Test
    public void readsSizeAndPixelFormatOutOfServerInit() throws IOException {
        server.desktop(1600, 1000, "кодоцех-стол");
        connect();

        assertEquals(1600, client.width());
        assertEquals(1000, client.height());
        assertEquals("кодоцех-стол", client.desktopName());
        assertEquals(1600, framebuffer.width());
        assertEquals(1000, framebuffer.height());
        assertTrue("формат сервера разобран не до конца", client.serverFormat().isBigEndian());
        assertEquals(32, client.serverFormat().bitsPerPixel());
        assertEquals(24, client.serverFormat().depth());
    }

    @Test
    public void switchesServerToOurPixelFormatBeforeAskingForAnything() throws IOException {
        connect();
        List<byte[]> messages = server.awaitMessages(3);

        assertEquals("первым делом формат пикселя", 0, messages.get(0)[0]);
        assertEquals("затем кодировки", 2, messages.get(1)[0]);
        assertEquals("и только потом кадр", 3, messages.get(2)[0]);

        byte[] expected = new byte[PixelFormat.LENGTH];
        PixelFormat.PREFERRED.write(expected, 0);
        byte[] sent = new byte[PixelFormat.LENGTH];
        System.arraycopy(messages.get(0), 4, sent, 0, PixelFormat.LENGTH);
        assertArrayEquals("сервер переключён не на наш формат", expected, sent);
    }

    @Test
    public void asksOnlyForRawAndCopyRect() throws IOException {
        connect();
        byte[] message = server.awaitMessage(2, 1);

        int count = u16(message, 2);
        List<Integer> encodings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            encodings.add(s32(message, 4 + i * 4));
        }
        assertTrue("нет Raw", encodings.contains(RfbClient.ENCODING_RAW));
        assertTrue("нет CopyRect", encodings.contains(RfbClient.ENCODING_COPY_RECT));
        assertTrue("нет Extended Clipboard",
                encodings.contains(RfbClient.PSEUDO_ENCODING_EXTENDED_CLIPBOARD));
        for (int encoding : new int[]{2, 5, 7, 16}) { // RRE, Hextile, Tight, ZRLE
            assertFalse("на петле сжатие не просим: " + encoding, encodings.contains(encoding));
        }
    }

    @Test
    public void refusalIsPassedOnWithTheReasonServerGave() throws IOException {
        server.refuseWith("стол уже занят");
        server.start();
        try {
            client.connect();
            fail("отказ сервера принят за успех");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("стол уже занят"));
        }
    }

    @Test
    public void foreignBannerIsNamedNotGuessed() throws IOException {
        server.banner("RFB 003.003\n");
        server.start();
        try {
            client.connect();
            fail("чужая версия протокола принята");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("RFB 003.003"));
        }
    }

    // --- кадры -----------------------------------------------------------------------

    @Test
    public void rawRectangleLandsInFrameWithBytesInTheRightOrder() throws IOException {
        server.desktop(2, 2, "стол");
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.rawRectangle(0, 0, 2, 2,
                new int[]{0xff0000, 0x00ff00, 0x0000ff, 0x123456})));
        connect();
        client.readServerMessage();

        assertEquals(0xffff0000, framebuffer.argbAt(0, 0));
        assertEquals(0xff00ff00, framebuffer.argbAt(1, 0));
        assertEquals(0xff0000ff, framebuffer.argbAt(0, 1));
        assertEquals("несимметричный цвет ловит перепутанные байты",
                0xff123456, framebuffer.argbAt(1, 1));
    }

    @Test
    public void rectangleGoesWhereItWasAddressed() throws IOException {
        server.desktop(4, 3, "стол");
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(2, 1, 2, 2,
                        new int[]{0x111111, 0x222222, 0x333333, 0x444444})));
        connect();
        client.readServerMessage();

        assertEquals(0xff111111, framebuffer.argbAt(2, 1));
        assertEquals(0xff222222, framebuffer.argbAt(3, 1));
        assertEquals(0xff333333, framebuffer.argbAt(2, 2));
        assertEquals(0xff444444, framebuffer.argbAt(3, 2));
        assertEquals("соседние точки трогать было нельзя", 0, framebuffer.argbAt(0, 0));
    }

    @Test
    public void copyRectMovesPixelsInsideTheFrame() throws IOException {
        server.desktop(4, 2, "стол");
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.rawRectangle(0, 0, 2, 1,
                new int[]{0xaabbcc, 0xddeeff})));
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.copyRectangle(0, 0, 2, 1, 2, 1)));
        connect();
        client.readServerMessage();
        client.readServerMessage();

        assertEquals(0xffaabbcc, framebuffer.argbAt(2, 1));
        assertEquals(0xffddeeff, framebuffer.argbAt(3, 1));
        assertEquals("исходник остаётся на месте", 0xffaabbcc, framebuffer.argbAt(0, 0));
    }

    @Test
    public void copyRectSurvivesOverlap() throws IOException {
        server.desktop(4, 1, "стол");
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.rawRectangle(0, 0, 4, 1,
                new int[]{0x010101, 0x020202, 0x030303, 0x040404})));
        // Сдвиг на один пиксель вправо — источник и приёмник перекрываются.
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.copyRectangle(0, 0, 1, 0, 3, 1)));
        connect();
        client.readServerMessage();
        client.readServerMessage();

        assertEquals(0xff010101, framebuffer.argbAt(1, 0));
        assertEquals(0xff020202, framebuffer.argbAt(2, 0));
        assertEquals(0xff030303, framebuffer.argbAt(3, 0));
    }

    @Test
    public void bellAndClipboardDoNotKnockTheStreamOutOfStep() throws IOException {
        server.desktop(1, 1, "стол");
        connect();
        server.send(FakeRfbServer.bell());
        server.send(FakeRfbServer.serverCutText("скопировано на столе"));
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x654321})));
        // Звонок и буфер обмена запроса кадра не требуют — сервер шлёт их подряд.
        assertEquals(2, client.readServerMessage());
        assertEquals(3, client.readServerMessage());
        assertEquals(0, client.readServerMessage());

        assertEquals(0xff654321, framebuffer.argbAt(0, 0));
    }

    @Test
    public void negativeLengthCutTextIsExtendedClipboardNotBrokenText() throws IOException {
        server.desktop(1, 1, "стол");
        connect();
        final boolean[] textReceived = {false};
        client.setCutTextListener(text -> textReceived[0] = true);

        server.send(FakeRfbServer.serverCutTextExtended(0x01, new byte[]{9, 9, 9}));
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x654321})));

        assertEquals("отрицательная длина — не ошибка протокола",
                3, client.readServerMessage());
        assertFalse("Extended Clipboard кадр не должен уйти в текстовый путь",
                textReceived[0]);
        // Разбор потока не сбит: следующий кадр читается как обычно.
        assertEquals(0, client.readServerMessage());
        assertEquals(0xff654321, framebuffer.argbAt(0, 0));
    }

    // --- Extended Clipboard: согласование (Caps, spec.md R01.1/R06.1) ----------------

    @Test
    public void sendsExtendedClipboardCapsRightAfterHandshake() throws IOException {
        connect();
        byte[] message = server.awaitMessage(6, 1);

        assertEquals("тип сообщения не ClientCutText", 6, message[0]);
        int length = s32(message, 4);
        assertEquals("Caps — это Text+DIB флаги плюс два maxSize", -12, length);
        int flags = s32(message, 8);
        assertEquals("флаги Caps|Text|DIB не сошлись",
                (1 << 24) | 1 | (1 << 3), flags);
        assertEquals("maxSize текста не 1 МБ", 1 << 20, s32(message, 12));
        assertEquals("maxSize DIB не 8 МБ", 8 << 20, s32(message, 16));
    }

    @Test
    public void serverCapsWithDibSwitchesClientIntoDibSupportedState() throws IOException {
        connect();
        assertFalse("до Caps DIB не может быть поддержан",
                client.isExtendedClipboardImageSupported());

        // Caps сервера: флаги Caps|Text|DIB, тело — maxSize(Text), maxSize(DIB).
        int flags = (1 << 24) | 1 | (1 << 3);
        byte[] body = new byte[8];
        putS32(body, 0, 1 << 20);
        putS32(body, 4, 4 << 20);
        server.send(FakeRfbServer.serverCutTextExtended(flags, body));
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x000000})));

        assertEquals(3, client.readServerMessage());
        assertTrue("сервер заявил DIB — клиент обязан это увидеть",
                client.isExtendedClipboardImageSupported());
    }

    @Test
    public void withoutServerCapsClientStaysWithoutDibAndDoesNotBreak() throws IOException {
        server.desktop(1, 1, "стол");
        connect();

        assertFalse("без Caps DIB не поддержан — это штатный откат",
                client.isExtendedClipboardImageSupported());

        // Обычный (не-Caps) Extended Clipboard кадр — например, чужое Notify/Request —
        // не должен переключать состояние и не должен ронять разбор потока.
        int notifyFlags = 1 << 27;
        server.send(FakeRfbServer.serverCutTextExtended(notifyFlags, new byte[0]));
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x654321})));

        assertEquals(3, client.readServerMessage());
        assertFalse("не-Caps кадр не должен включать DIB",
                client.isExtendedClipboardImageSupported());
        assertEquals(0, client.readServerMessage());
        assertEquals(0xff654321, framebuffer.argbAt(0, 0));
    }

    @Test
    public void unknownEncodingIsLoudInsteadOfSilentlyBreakingTheFrame() throws IOException {
        server.desktop(2, 2, "стол");
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.unknownEncodingRectangle(7)));
        connect();
        try {
            client.readServerMessage();
            fail("чужая кодировка проглочена молча");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("7"));
        }
    }

    // --- Extended Clipboard: Provide, картинки (spec.md R02.1/R02.2) -----------------

    @Test
    public void sendsExtendedClipboardImageAsZlibCompressedProvideFrame()
            throws IOException, java.util.zip.DataFormatException {
        connect();
        negotiateServerDib(8 << 20);

        byte[] dib = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        client.sendExtendedClipboardImage(dib);

        // Сообщение № 1 типа 6 — Caps, которую connect() шлёт сам; это второе.
        byte[] message = server.awaitMessage(6, 2);
        assertEquals("тип сообщения не ClientCutText", 6, message[0]);
        int length = s32(message, 4);
        assertTrue("Provide должен идти отрицательной длиной", length < 0);
        int flags = s32(message, 8);
        assertEquals("флаги Provide|DIB не сошлись",
                RfbClient.EXT_CLIPBOARD_ACTION_PROVIDE | RfbClient.EXT_CLIPBOARD_FORMAT_DIB, flags);

        int payloadLength = -length;
        byte[] compressed = new byte[payloadLength - 4];
        System.arraycopy(message, 12, compressed, 0, compressed.length);
        byte[] decompressed = inflate(compressed);
        int dataLength = s32(decompressed, 0);
        assertEquals(dib.length, dataLength);
        byte[] recovered = new byte[dataLength];
        System.arraycopy(decompressed, 4, recovered, 0, dataLength);
        assertArrayEquals("после распаковки должны получиться исходные байты", dib, recovered);
    }

    @Test
    public void sendExtendedClipboardImageDoesNothingWithoutNegotiatedSupport()
            throws IOException, InterruptedException {
        connect();
        server.awaitMessage(6, 1); // Caps, которую connect() шлёт сам — дождаться, чтобы не гнаться за ней ниже
        assertFalse(client.isExtendedClipboardImageSupported());

        int before = server.receivedCount();
        client.sendExtendedClipboardImage(new byte[]{1, 2, 3, 4});
        Thread.sleep(150);

        assertEquals("без согласованного DIB отправка обязана быть no-op",
                before, server.receivedCount());
    }

    @Test
    public void sendExtendedClipboardImageDoesNothingWhenBiggerThanServerMax()
            throws IOException, InterruptedException {
        connect();
        negotiateServerDib(4);

        int before = server.receivedCount();
        client.sendExtendedClipboardImage(new byte[]{1, 2, 3, 4, 5});
        Thread.sleep(150);

        assertEquals("картинка больше объявленного maxUnsolicitedSize — тихий пропуск",
                before, server.receivedCount());
    }

    @Test
    public void receivingProvideWithDibCallsImageListener() throws IOException {
        server.desktop(1, 1, "стол");
        connect();
        final byte[][] received = {null};
        client.setExtendedClipboardImageListener(bytes -> received[0] = bytes);

        byte[] dib = validDib(2, 2);
        byte[] compressed = deflate(formatBlock(dib));
        int flags = RfbClient.EXT_CLIPBOARD_ACTION_PROVIDE | RfbClient.EXT_CLIPBOARD_FORMAT_DIB;
        server.send(FakeRfbServer.serverCutTextExtendedProvide(flags, compressed));
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x654321})));

        assertEquals(3, client.readServerMessage());
        assertArrayEquals("DIB не дошёл до слушателя байт в байт", dib, received[0]);
        // Разбор потока не сбит: следующий кадр читается как обычно.
        assertEquals(0, client.readServerMessage());
        assertEquals(0xff654321, framebuffer.argbAt(0, 0));
    }

    @Test
    public void corruptedZlibInProvideIsIgnoredWithoutBreakingConnection() throws IOException {
        server.desktop(1, 1, "стол");
        connect();
        final boolean[] imageReceived = {false};
        client.setExtendedClipboardImageListener(bytes -> imageReceived[0] = true);

        int flags = RfbClient.EXT_CLIPBOARD_ACTION_PROVIDE | RfbClient.EXT_CLIPBOARD_FORMAT_DIB;
        byte[] garbage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8}; // не начинается с zlib-заголовка
        server.send(FakeRfbServer.serverCutTextExtendedProvide(flags, garbage));
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x654321})));

        assertEquals("битый zlib не должен ронять разбор потока и рвать соединение",
                3, client.readServerMessage());
        assertFalse("на битых данных слушатель звать нельзя", imageReceived[0]);
        assertEquals(0, client.readServerMessage());
        assertEquals(0xff654321, framebuffer.argbAt(0, 0));
    }

    private void negotiateServerDib(int maxDibSize) throws IOException {
        server.awaitMessage(6, 1); // Caps, которую connect() шлёт сам
        int flags = RfbClient.EXT_CLIPBOARD_ACTION_CAPS | RfbClient.EXT_CLIPBOARD_FORMAT_TEXT
                | RfbClient.EXT_CLIPBOARD_FORMAT_DIB;
        byte[] body = new byte[8];
        putS32(body, 0, 1 << 20);
        putS32(body, 4, maxDibSize);
        server.send(FakeRfbServer.serverCutTextExtended(flags, body));
        assertEquals(3, client.readServerMessage());
    }

    /** Минимальный правдоподобный DIB: BITMAPINFOHEADER (little-endian) + пиксели-заглушка. */
    private static byte[] validDib(int width, int height) {
        byte[] dib = new byte[40 + width * height * 3];
        putLeS32(dib, 0, 40);
        putLeS32(dib, 4, width);
        putLeS32(dib, 8, height);
        return dib;
    }

    private static byte[] formatBlock(byte[] data) {
        byte[] block = new byte[4 + data.length];
        putS32(block, 0, data.length);
        System.arraycopy(data, 0, block, 4, data.length);
        return block;
    }

    private static byte[] deflate(byte[] input) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(input);
        deflater.finish();
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            result.write(buffer, 0, count);
        }
        deflater.end();
        return result.toByteArray();
    }

    private static byte[] inflate(byte[] input) throws java.util.zip.DataFormatException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(input);
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            if (count == 0 && inflater.needsInput()) {
                break;
            }
            result.write(buffer, 0, count);
        }
        inflater.end();
        return result.toByteArray();
    }

    private static void putLeS32(byte[] message, int at, int value) {
        message[at] = (byte) value;
        message[at + 1] = (byte) (value >>> 8);
        message[at + 2] = (byte) (value >>> 16);
        message[at + 3] = (byte) (value >>> 24);
    }

    // --- запросы кадра ---------------------------------------------------------------

    @Test
    public void firstRequestIsWholeScreenAndTheNextOnesAreIncremental() throws IOException {
        server.desktop(800, 600, "стол");
        server.enqueue(FakeRfbServer.frame(
                FakeRfbServer.rawRectangle(0, 0, 1, 1, new int[]{0x000000})));
        connect();

        byte[] first = server.awaitMessage(3, 1);
        assertEquals("первый кадр — целиком", 0, first[1]);
        assertEquals(0, u16(first, 2));
        assertEquals(0, u16(first, 4));
        assertEquals(800, u16(first, 6));
        assertEquals(600, u16(first, 8));

        client.readServerMessage();
        byte[] second = server.awaitMessage(3, 2);
        assertEquals("дальше просим только изменения", 1, second[1]);
    }

    @Test
    public void resizedDesktopIsAskedForWholeAgain() throws IOException {
        server.desktop(800, 600, "стол");
        server.enqueue(FakeRfbServer.frame(FakeRfbServer.resizeRectangle(1280, 720)));
        connect();
        client.readServerMessage();

        assertEquals(1280, client.width());
        assertEquals(720, framebuffer.height());
        byte[] second = server.awaitMessage(3, 2);
        assertEquals("после смены размера прежнего кадра нет", 0, second[1]);
        assertEquals(1280, u16(second, 6));
        assertEquals(720, u16(second, 8));
    }

    // --- ввод ------------------------------------------------------------------------

    @Test
    public void pointerEventGoesOutByteForByte() throws IOException {
        connect();
        client.sendPointer(300, 200, RfbClient.BUTTON_LEFT);

        assertArrayEquals(new byte[]{5, 1, 0x01, 0x2c, 0x00, (byte) 0xc8},
                server.awaitMessage(5, 1));
    }

    @Test
    public void rightButtonIsButtonThree() throws IOException {
        connect();
        client.sendPointer(1, 2, RfbClient.BUTTON_RIGHT);

        byte[] message = server.awaitMessage(5, 1);
        assertEquals("правая кнопка — третий бит маски", 0b100, message[1]);
    }

    @Test
    public void wheelIsButtonsFourAndFivePressedAndReleased() throws IOException {
        connect();
        client.sendWheel(10, 20, true);
        client.sendWheel(10, 20, false);

        assertEquals("колесо вверх — кнопка 4", 0b1000, server.awaitMessage(5, 1)[1]);
        assertEquals("кнопку надо отпустить", 0, server.awaitMessage(5, 2)[1]);
        assertEquals("колесо вниз — кнопка 5", 0b10000, server.awaitMessage(5, 3)[1]);
        assertEquals(0, server.awaitMessage(5, 4)[1]);
    }

    @Test
    public void keyEventCarriesKeysymInNetworkOrder() throws IOException {
        connect();
        client.sendKey(Keysyms.ENTER, true);
        client.sendKey(Keysyms.ENTER, false);

        assertArrayEquals(new byte[]{4, 1, 0, 0, 0, 0, (byte) 0xff, 0x0d},
                server.awaitMessage(4, 1));
        assertArrayEquals(new byte[]{4, 0, 0, 0, 0, 0, (byte) 0xff, 0x0d},
                server.awaitMessage(4, 2));
    }

    @Test
    public void unicodeKeysymSurvivesTheWire() throws IOException {
        connect();
        client.sendKey(Keysyms.forCharacter('ж'), true);

        byte[] message = server.awaitMessage(4, 1);
        assertEquals(0x01000436, s32(message, 4));
    }

    @Test
    public void cutTextSentCorrectlyAcrossTheWire() throws IOException {
        connect();
        client.sendCutText("Привет, мир!");

        // Сообщение № 1 типа 6 — Caps Extended Clipboard, которую connect() шлёт сам
        // (см. sendsExtendedClipboardCapsRightAfterHandshake); это второе.
        byte[] message = server.awaitMessage(6, 2);
        assertEquals("тип сообщения не ClientCutText", 6, message[0]);
        int len = s32(message, 4);
        assertEquals(new String(message, 8, len, java.nio.charset.StandardCharsets.UTF_8), "Привет, мир!");
    }

    // --- граница на петле ------------------------------------------------------------

    @Test
    public void thereIsNoWayToDialAnythingButLoopback() throws Exception {
        for (Constructor<?> constructor : RfbClient.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse("клиент принимает адрес: " + constructor,
                        parameter == String.class || InetAddress.class.isAssignableFrom(parameter));
            }
        }
        for (Method method : RfbClient.class.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse("клиенту можно подсунуть адрес: " + method,
                        InetAddress.class.isAssignableFrom(parameter));
            }
        }
        assertEquals(5901, RfbClient.DEFAULT_PORT);
    }

    private static int u16(byte[] message, int at) {
        return ((message[at] & 0xff) << 8) | (message[at + 1] & 0xff);
    }

    private static int s32(byte[] message, int at) {
        return ((message[at] & 0xff) << 24) | ((message[at + 1] & 0xff) << 16)
                | ((message[at + 2] & 0xff) << 8) | (message[at + 3] & 0xff);
    }

    private static void putS32(byte[] message, int at, int value) {
        message[at] = (byte) (value >>> 24);
        message[at + 1] = (byte) (value >>> 16);
        message[at + 2] = (byte) (value >>> 8);
        message[at + 3] = (byte) value;
    }
}
