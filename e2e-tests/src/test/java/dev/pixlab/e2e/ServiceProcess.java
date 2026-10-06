package dev.pixlab.e2e;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Um serviço Spring Boot rodando como processo próprio, a partir do bootJar. */
final class ServiceProcess implements AutoCloseable {

    private final String name;
    private final int port;
    private final Process process;
    private final Path log;

    private ServiceProcess(String name, int port, Process process, Path log) {
        this.name = name;
        this.port = port;
        this.process = process;
        this.log = log;
    }

    static ServiceProcess start(String name, String jarProperty, List<String> args) throws IOException {
        return start(name, jarProperty, args, freePort());
    }

    static ServiceProcess start(String name, String jarProperty, List<String> args, int port) throws IOException {
        var jar = System.getProperty(jarProperty);
        var logs = Path.of(System.getProperty("pixlab.e2e.logs"));
        Files.createDirectories(logs);
        var log = logs.resolve(name + ".log");

        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-jar");
        command.add(jar);
        command.add("--server.port=" + port);
        command.addAll(args);

        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var service = new ServiceProcess(name, port, process, log);
        service.awaitHealthy(Duration.ofSeconds(60));
        return service;
    }

    URI baseUrl() {
        return URI.create("http://localhost:" + port);
    }

    static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitHealthy(Duration timeout) throws IOException {
        var http = HttpClient.newHttpClient();
        var health = HttpRequest.newBuilder(baseUrl().resolve("/actuator/health")).build();
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " morreu ao subir; veja " + log);
            }
            try {
                var response = http.send(health, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException e) {
                // ainda subindo
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            sleep();
        }
        close();
        throw new IllegalStateException(name + " não ficou UP em " + timeout + "; veja " + log);
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(15))) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
