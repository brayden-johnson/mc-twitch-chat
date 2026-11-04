package com.braydenjohnson.mctwitchchat.irc;

import com.braydenjohnson.mctwitchchat.MCTwitchChat;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Read-only Twitch chat connection. Logs in anonymously as justinfanNNNNN, so no account,
 * OAuth token or stream key is ever needed. Reconnects on its own with backoff.
 */
public final class TwitchIrcClient implements Runnable {
    public interface StatusListener {
        void status(String text, boolean announce);
    }

    private static final String HOST = "irc.chat.twitch.tv";
    private static final int PORT = 6697;

    private final String channel;
    private final Consumer<IrcMessage> handler;
    private final StatusListener statusListener;
    private volatile boolean running = true;
    private volatile Socket socket;
    private Thread thread;

    public TwitchIrcClient(String channel, Consumer<IrcMessage> handler, StatusListener statusListener) {
        this.channel = channel;
        this.handler = handler;
        this.statusListener = statusListener;
    }

    public String channel() {
        return channel;
    }

    public void start() {
        thread = new Thread(this, "MCTwitchChat-IRC-" + channel);
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        int backoff = 1;
        while (running) {
            boolean[] joined = {false};
            try {
                statusListener.status("Connecting to #" + channel + "…", false);
                connectAndRead(joined);
            } catch (Exception e) {
                if (!running) break;
                MCTwitchChat.LOG.info("Twitch chat connection dropped: {}", e.toString());
            }
            if (!running) break;
            if (joined[0]) backoff = 1;
            statusListener.status("Disconnected from #" + channel + ", retrying in " + backoff + "s", joined[0]);
            try {
                Thread.sleep(backoff * 1000L);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 30);
        }
        statusListener.status("Not connected", false);
    }

    private void connectAndRead(boolean[] joined) throws IOException {
        Socket plain = new Socket();
        socket = plain;
        plain.connect(new InetSocketAddress(HOST, PORT), 10_000);
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, HOST, PORT, true);
        socket = s;
        // Twitch PINGs about every 5 minutes; if nothing arrives for 6, the link is dead.
        s.setSoTimeout(6 * 60 * 1000);
        s.startHandshake();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String nick = "justinfan" + ThreadLocalRandom.current().nextInt(10000, 99999);
            send(out, "CAP REQ :twitch.tv/tags twitch.tv/commands");
            send(out, "PASS SCHMOOPIIE");
            send(out, "NICK " + nick);
            send(out, "JOIN #" + channel);
            String line;
            while (running && (line = in.readLine()) != null) {
                IrcMessage msg = IrcMessage.parse(line);
                if (msg == null) continue;
                switch (msg.command) {
                    case "PING" -> send(out, "PONG :" + (msg.trailing == null ? "tmi.twitch.tv" : msg.trailing));
                    case "RECONNECT" -> throw new IOException("Twitch asked us to reconnect");
                    case "JOIN" -> {
                        if (msg.login().equals(nick)) {
                            joined[0] = true;
                            statusListener.status("Connected to #" + channel, true);
                        }
                    }
                    default -> {
                        try {
                            handler.accept(msg);
                        } catch (Exception e) {
                            MCTwitchChat.LOG.warn("Error handling Twitch message: {}", line, e);
                        }
                    }
                }
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        throw new IOException("Connection closed");
    }

    private static void send(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }
}
