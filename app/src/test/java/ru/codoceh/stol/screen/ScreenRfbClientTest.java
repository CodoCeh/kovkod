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

        byte[] message = server.awaitMessage(6, 1);
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
}
