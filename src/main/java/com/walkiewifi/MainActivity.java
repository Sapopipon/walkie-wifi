package com.walkiewifi;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

/**
 * Walkie-talkie por Wi-Fi local.
 * Cada teléfono manda el audio por "broadcast" a toda la red y los demás
 * lo escuchan si están en el mismo canal. No hace falta servidor ni internet.
 */
public class MainActivity extends Activity {

    private static final int PORT = 45678;
    private static final int RATE = 16000;
    private static final int FRAME_BYTES = 640; // 20 milisegundos de audio
    private static final int HEADER = 6;
    private static final byte MAGIC = 0x57;
    private static final int MAX_CHANNEL = 9;
    private static final String READY = "Listo. Mantén apretado el botón para hablar.";

    private final int myId = new Random().nextInt();
    private volatile int channel = 1;
    private volatile boolean talking = false;
    private volatile boolean receiving = false;
    private volatile int txSession = 0;
    private volatile int rxSession = 0;

    private TextView statusView;
    private TextView channelView;
    private Button talkButton;
    private GradientDrawable talkBg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        final int sid = ++rxSession;
        new Thread(() -> rxLoop(sid)).start();
    }

    @Override
    protected void onStop() {
        rxSession++;
        talking = false;
        receiving = false;
        setTalkingUi(false);
        super.onStop();
    }

    // ---------------------------------------------------------------- pantalla

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101820"));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = text("Walkie Wi-Fi", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button minus = new Button(this);
        minus.setText("-");
        minus.setOnClickListener(v -> changeChannel(-1));

        channelView = text("Canal 1", 22, Color.WHITE);
        channelView.setPadding(dp(24), 0, dp(24), 0);

        Button plus = new Button(this);
        plus.setText("+");
        plus.setOnClickListener(v -> changeChannel(1));

        row.addView(minus);
        row.addView(channelView);
        row.addView(plus);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(24);
        root.addView(row, rowParams);

        statusView = text(READY, 16, Color.LTGRAY);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(24);
        statusParams.bottomMargin = dp(24);
        root.addView(statusView, statusParams);

        talkBg = new GradientDrawable();
        talkBg.setShape(GradientDrawable.OVAL);
        talkBg.setColor(Color.parseColor("#2E7D32"));

        talkButton = new Button(this);
        talkButton.setText("HABLAR");
        talkButton.setTextSize(22);
        talkButton.setTextColor(Color.WHITE);
        talkButton.setTypeface(Typeface.DEFAULT_BOLD);
        talkButton.setBackground(talkBg);
        talkButton.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startTalking();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopTalking();
                    return true;
                default:
                    return true;
            }
        });
        root.addView(talkButton, new LinearLayout.LayoutParams(dp(220), dp(220)));

        setContentView(root);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void changeChannel(int delta) {
        channel = Math.max(1, Math.min(MAX_CHANNEL, channel + delta));
        channelView.setText("Canal " + channel);
    }

    private void setTalkingUi(boolean on) {
        talkBg.setColor(Color.parseColor(on ? "#C62828" : "#2E7D32"));
        talkButton.setText(on ? "HABLANDO..." : "HABLAR");
        if (on) {
            statusView.setText("Hablando en el canal " + channel + "...");
        } else if (!receiving) {
            statusView.setText(READY);
        }
    }

    private void showReceiving(final boolean on) {
        if (receiving == on) return;
        receiving = on;
        runOnUiThread(() -> {
            if (!talking) {
                statusView.setText(on ? "Recibiendo audio..." : READY);
            }
        });
    }

    private void fail(final String msg) {
        talking = false;
        runOnUiThread(() -> {
            setTalkingUi(false);
            statusView.setText(msg);
        });
    }

    // ------------------------------------------------------------------ hablar

    private void startTalking() {
        if (talking) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            statusView.setText("Da permiso al micrófono y vuelve a intentar.");
            return;
        }
        talking = true;
        setTalkingUi(true);
        final int sid = ++txSession;
        final List<InetAddress> targets = broadcastTargets();
        new Thread(() -> txLoop(sid, targets)).start();
    }

    private void stopTalking() {
        talking = false;
        setTalkingUi(false);
    }

    private List<InetAddress> broadcastTargets() {
        List<InetAddress> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !list.contains(b)) list.add(b);
                }
            }
        } catch (Exception ignored) {
            // si falla, se usa la dirección general de más abajo
        }
        if (list.isEmpty()) {
            try {
                list.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
                // nada
            }
        }
        return list;
    }

    private void txLoop(int sid, List<InetAddress> targets) {
        AudioRecord rec = null;
        DatagramSocket sock = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, FRAME_BYTES * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                fail("No se pudo abrir el micrófono.");
                return;
            }
            sock = new DatagramSocket();
            sock.setBroadcast(true);

            byte[] buf = new byte[HEADER + FRAME_BYTES];
            buf[0] = MAGIC;
            buf[2] = (byte) (myId >>> 24);
            buf[3] = (byte) (myId >>> 16);
            buf[4] = (byte) (myId >>> 8);
            buf[5] = (byte) myId;

            rec.startRecording();
            while (talking && sid == txSession) {
                buf[1] = (byte) channel;
                int n = rec.read(buf, HEADER, FRAME_BYTES);
                if (n < 0) {
                    fail("Error del micrófono.");
                    break;
                }
                if (n == 0) continue;
                for (InetAddress a : targets) {
                    sock.send(new DatagramPacket(buf, HEADER + n, a, PORT));
                }
            }
        } catch (Exception e) {
            fail("Error al transmitir: " + e.getMessage());
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                    // nada
                }
                rec.release();
            }
            if (sock != null) sock.close();
        }
    }

    // ----------------------------------------------------------------- escuchar

    private AudioTrack createTrack() {
        int min = AudioTrack.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, FRAME_BYTES * 10);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private void rxLoop(int sid) {
        DatagramSocket sock = null;
        AudioTrack track = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(PORT));
            sock.setBroadcast(true);
            sock.setSoTimeout(500);
            track = createTrack();

            byte[] buf = new byte[HEADER + FRAME_BYTES * 2];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            long last = 0;
            int queued = 0;
            boolean started = false;

            while (sid == rxSession) {
                packet.setLength(buf.length);
                try {
                    sock.receive(packet);
                } catch (SocketTimeoutException e) {
                    if (receiving && SystemClock.elapsedRealtime() - last > 500) {
                        showReceiving(false);
                    }
                    continue;
                }

                int len = packet.getLength();
                if (len <= HEADER || buf[0] != MAGIC) continue;
                if ((buf[1] & 0xFF) != channel) continue;
                int senderId = ((buf[2] & 0xFF) << 24) | ((buf[3] & 0xFF) << 16)
                        | ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
                if (senderId == myId) continue;

                int dataLen = (len - HEADER) & ~1;
                if (dataLen <= 0) continue;

                long now = SystemClock.elapsedRealtime();
                if (now - last > 400) {
                    // empezó una transmisión nueva: se vacía y se junta un poquito de audio
                    track.pause();
                    track.flush();
                    started = false;
                    queued = 0;
                }
                last = now;

                track.write(buf, HEADER, dataLen);
                queued++;
                if (!started && queued >= 3) {
                    track.play();
                    started = true;
                }
                showReceiving(true);
            }
        } catch (Exception e) {
            if (sid == rxSession) {
                runOnUiThread(() -> statusView.setText("No se pudo escuchar la red Wi-Fi."));
            }
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {
                    // nada
                }
                track.release();
            }
            if (sock != null) sock.close();
        }
    }
}
