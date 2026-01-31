package com.pingidentity.opendst.testapp;

import static java.lang.Integer.parseInt;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.lang.Thread.sleep;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Server {
    // The sequence of numbers required to advance levels
    private static final int[] SECRET_SEQUENCE = { 1, 2, 3, 4, 5 };

    private final Lock lock = new ReentrantLock();

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            err.println("Usage: Server <port>");
            exit(1);
        }
        try (var serverSocket = new ServerSocket(parseInt(args[0]))) {
            out.println("Server listening on port " + serverSocket.getLocalPort());
            new Server().run(serverSocket);
        }
    }

    public void run(ServerSocket serverSocket) {
        int currentLevel = 0;
        out.println("Level: " + currentLevel);

        try (var socket = serverSocket.accept();
             var in = new DataInputStream(socket.getInputStream())) {
            for (int code = in.readInt();; code = in.readInt()) {
                lock.lock();
                try {
                    // Simulate some processing time holding the lock
                    sleep(10);
                    if (currentLevel < SECRET_SEQUENCE.length && code == SECRET_SEQUENCE[currentLevel]) {
                        currentLevel++;
                        out.printf("Level %d unlocked%n", currentLevel);
                        if (currentLevel == SECRET_SEQUENCE.length) {
                            out.println("Goal Reached!");
                            currentLevel = 0;
                        }
                    } else if (currentLevel > 0) {
                        currentLevel = 0;
                        out.println("Reset");
                        out.println("Level: " + currentLevel);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
        } catch (IOException e) {
            // End of stream or error
        }
    }
}
