package com.pingidentity.opendst.testapp.dst;

import static com.pingidentity.opendst.Deployment.Image.image;
import static com.pingidentity.opendst.Deployment.Service.service;
import static com.pingidentity.opendst.Deployment.deployment;
import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.ofLiteral;
import static java.util.List.of;

import com.pingidentity.opendst.Deployment;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

public class DST {
    public static final Path WAR = Path.of("opendst-testapp");

    public void run() throws IOException {
        runSimulation(deployment(
                of(image("server-image", WAR, "com.pingidentity.opendst.testapp.Server"),
                   image("client-image", WAR, "com.pingidentity.opendst.testapp.Client")),
                of(service("server", "server-image", ofLiteral("10.0.0.2"), new String[]{ "8080"}),
                   service("client", "client-image", ofLiteral("10.0.0.3"), new String[]{"10.0.0.2", "8080"}))));
    }
}
