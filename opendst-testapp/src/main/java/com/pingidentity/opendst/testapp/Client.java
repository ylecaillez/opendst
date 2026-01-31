package com.pingidentity.opendst.testapp;

import static java.lang.System.err;
import static java.lang.System.exit;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Random;

public class Client {
    private static final int MAX_VALUE = 20;

    public static void main(String[] args) {
        if (args.length < 2) {
            err.println("Usage: Client <host> <port>");
            exit(1);
        }
        new Client().run(args[0], Integer.parseInt(args[1]));
    }

    public void run(String host, int port) {
        var random = new Random();
        var monitor = new Object();
        try (var socket = new Socket(host, port);
             var out = new DataOutputStream(socket.getOutputStream())) {
            for (;;) {
                int nextGuess = random.nextInt(MAX_VALUE);
                out.writeInt(nextGuess);
                out.flush();
                synchronized (monitor) {
                    monitor.wait(1_000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            err.println("Error connecting to " + host + ":" + port + ": " + e.getMessage());
        }
    }
}
