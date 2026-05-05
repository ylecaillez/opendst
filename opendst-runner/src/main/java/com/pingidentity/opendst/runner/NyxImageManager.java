/*
 * Copyright 2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Manages nyx-lite VM assets extracted from a Docker base image and per-run deployment images.
 *
 * <h2>Base image cache</h2>
 * The rootfs (ext4 image) and shim binary are extracted once per base image digest and cached
 * under {@code ~/.opendst/nyx-rootfs/<digest>/}. Subsequent runs reuse the cache without
 * re-extracting. The base image only contains the OS, JDK, and guest bridge; it has no
 * application-specific content.
 *
 * <h2>Deployment ext2 image</h2>
 * The deployment directory (JARs, deployment.yaml, instrumented apps) is packed into a small
 * ext2 image ({@code deployment.ext4}) by {@link #buildDeploymentExt2} and passed to the
 * Firecracker VM as a second virtio-blk drive ({@code /dev/vdb}). The guest init script mounts
 * it at {@code /opendst-deployment/} before launching the JVM.
 *
 * <h2>Cache layout</h2>
 * <pre>
 * ~/.opendst/nyx-rootfs/&lt;digest&gt;/
 *   rootfs.ext4          — VM disk image (extracted from base Docker image, permanent)
 *   opendst-nyx-shim     — shim binary (extracted from /usr/bin/opendst-nyx-shim, permanent)
 *
 * &lt;workingDir&gt;/
 *   deployment.ext4      — deployment ext2 image (built per run from JAR contents)
 *   vmconfig.json        — generated Firecracker config (references both drives)
 * </pre>
 */
final class NyxImageManager {

    private static final String SHIM_PATH_IN_IMAGE = "/usr/bin/opendst-nyx-shim";
    private static final String DEFAULT_KERNEL =
            System.getProperty("user.home") + "/git/nyx-lite/vm_image/vmlinux-6.1.58";
    private static final String NYX_CACHE_DIR = System.getProperty("user.home") + "/.opendst/nyx-rootfs";

    /** Result of {@link #prepare}. */
    record NyxSetup(Path shimBinary, String vmConfigPath) {}

    /**
     * Prepares all VM assets needed to start the shim for one simulation run:
     * <ol>
     *   <li>Resolves the base image digest and extracts rootfs + shim into the permanent cache
     *       (skipped if already cached).</li>
     *   <li>Packs the deployment directory into a small ext2 image under {@code workingDir}.</li>
     *   <li>Writes {@code vmconfig.json} under {@code workingDir} referencing both drives.</li>
     * </ol>
     *
     * @param baseImageTag  Docker base image tag (e.g. {@code opendst-nyx-base:latest})
     * @param deploymentDir path to the extracted deployment directory (from the -opendst.jar)
     * @param workingDir    per-run working directory where deployment.ext4 and vmconfig.json are written
     * @param logger        for progress output
     */
    static NyxSetup prepare(String baseImageTag, Path deploymentDir, Path workingDir, OpenDstLogger logger)
            throws IOException, InterruptedException {
        // 1. Permanent base image cache (rootfs + shim)
        var digest = resolveDigest(baseImageTag);
        var cacheDir = Path.of(NYX_CACHE_DIR, digest);
        var rootfsPath = cacheDir.resolve("rootfs.ext4");
        var shimPath = cacheDir.resolve("opendst-nyx-shim");

        if (!Files.exists(rootfsPath) || !Files.exists(shimPath)) {
            logger.raw().info("Extracting nyx-lite base image: " + baseImageTag);
            Files.createDirectories(cacheDir);
            extractRootfs(baseImageTag, rootfsPath, logger);
            extractShim(baseImageTag, shimPath, logger);
            logger.raw().info("Base image extraction complete: " + cacheDir);
        } else {
            logger.raw().debug("Using cached nyx-lite base image: " + cacheDir);
        }

        // 2. Per-run deployment ext2 image.
        // When launched via the -opendst.jar, Bootstrap has already extracted the deployment to
        // workingDir/deployment/.  When launched directly with a legacy per-project image that has
        // the deployment baked in at /deployment/, extract it from the image on demand.
        var resolvedDeploymentDir = deploymentDir;
        if (!Files.exists(deploymentDir)) {
            logger.raw().info("Deployment dir not found locally; extracting from image: " + baseImageTag);
            resolvedDeploymentDir = workingDir.resolve("deployment");
            extractDeploymentFromImage(baseImageTag, resolvedDeploymentDir, logger);
        }
        var deploymentExt4 = workingDir.resolve("deployment.ext4");
        logger.raw().info("Building deployment.ext4 from: " + resolvedDeploymentDir);
        buildDeploymentExt2(resolvedDeploymentDir, deploymentExt4, logger);

        // 3. Firecracker vmconfig (references both drives)
        var vmConfigPath = workingDir.resolve("vmconfig.json");
        writeVmConfig(rootfsPath, deploymentExt4, vmConfigPath);

        return new NyxSetup(shimPath, vmConfigPath.toString());
    }

    /**
     * Packs {@code deploymentDir} into a small ext2 image at {@code outputPath}.
     *
     * <p>The image size is calculated as the actual directory size plus 30% overhead,
     * with a minimum of 64 MB.
     */
    static void buildDeploymentExt2(Path deploymentDir, Path outputPath, OpenDstLogger logger)
            throws IOException, InterruptedException {
        // Calculate actual content size in bytes
        long sizeBytes;
        try (var stream = Files.walk(deploymentDir)) {
            sizeBytes = stream.filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        }
        // Add 30% overhead; convert to 1KB blocks; minimum 65536 blocks (64 MB)
        long blocks = Math.max(65536L, (long) (sizeBytes * 1.3 / 1024) + 2048);
        logger.raw().info("  Building deployment.ext4 (" + (blocks / 1024) + " MB, content "
                + (sizeBytes / 1024 / 1024) + " MB)...");
        run("genext2fs", "-d", deploymentDir.toString(), "-b", Long.toString(blocks), outputPath.toString());
    }

    /**
     * Resolves the Docker image digest (short form) for use as a cache key.
     * Uses {@code docker inspect --format '{{.Id}}'} and takes the first 12 hex chars.
     */
    private static String resolveDigest(String imageTag) throws IOException, InterruptedException {
        var proc = new ProcessBuilder("docker", "inspect", "--format", "{{.Id}}", imageTag)
                .redirectErrorStream(true)
                .start();
        var output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (proc.waitFor() != 0 || output.isEmpty()) {
            throw new IOException("Failed to inspect image '" + imageTag + "': " + output);
        }
        // sha256:abcdef... → take 12 chars after the colon (or after sha256:)
        var id = output.contains(":") ? output.substring(output.indexOf(':') + 1) : output;
        return id.substring(0, Math.min(12, id.length()));
    }

    /**
     * Extracts the full Docker image filesystem to an ext2 image using {@code genext2fs}.
     *
     * <p>No root/sudo required: uses {@code docker export} to stream the container
     * filesystem as a tar archive, extracts it to a staging directory, then calls
     * {@code genext2fs} to build the ext2 image file directly from that directory.
     */
    private static void extractRootfs(String imageTag, Path rootfsPath, OpenDstLogger logger)
            throws IOException, InterruptedException {
        // 1. Create a stopped container from the image
        var createProc = new ProcessBuilder("docker", "create", imageTag)
                .redirectErrorStream(true)
                .start();
        var containerId = new String(createProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (createProc.waitFor() != 0) {
            throw new IOException("docker create failed: " + containerId);
        }
        logger.raw().info("  Container: " + containerId.substring(0, 12));

        var stagingDir = rootfsPath.getParent().resolve("staging");
        try {
            // 2. Export container filesystem as tar and extract to staging dir
            Files.createDirectories(stagingDir);
            logger.raw().info("  Exporting container filesystem...");
            var exportProc = new ProcessBuilder("docker", "export", containerId)
                    .redirectErrorStream(false)
                    .start();
            var tarProc = new ProcessBuilder("tar", "-x", "-C", stagingDir.toString())
                    .redirectErrorStream(true)
                    .start();
            // Pipe export→tar in a background thread to avoid deadlock on large images
            var pipe = Thread.ofPlatform().start(() -> {
                try {
                    exportProc.getInputStream().transferTo(tarProc.getOutputStream());
                    tarProc.getOutputStream().close();
                } catch (IOException ignored) {
                }
            });
            int exportCode = exportProc.waitFor();
            pipe.join();
            int tarCode = tarProc.waitFor();
            if (exportCode != 0) throw new IOException("docker export failed (exit " + exportCode + ")");
            if (tarCode != 0) throw new IOException("tar extraction failed (exit " + tarCode + ")");

            // 3. Build ext2 image from staging dir — no root needed
            logger.raw().info("  Building ext2 image with genext2fs (800MB)...");
            run(
                    "genext2fs",
                    "-d",
                    stagingDir.toString(),
                    "-b",
                    "819200", // 800 MB in 1 KB blocks
                    "-N",
                    "65536", // inodes
                    rootfsPath.toString());
        } finally {
            deleteDir(stagingDir);
            new ProcessBuilder("docker", "rm", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Extracts the {@code /deployment} directory from a Docker image into {@code destDir}.
     *
     * <p>Used when the runner is invoked directly with a per-project image that has the
     * deployment baked in at {@code /deployment/} (legacy workflow).  The normal path is that
     * Bootstrap already extracted the deployment from the self-contained JAR before calling
     * {@link BuildRunner}.
     */
    private static void extractDeploymentFromImage(String imageTag, Path destDir, OpenDstLogger logger)
            throws IOException, InterruptedException {
        var createProc = new ProcessBuilder("docker", "create", imageTag)
                .redirectErrorStream(true)
                .start();
        var containerId = new String(createProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (createProc.waitFor() != 0) {
            throw new IOException("docker create failed for deployment extraction: " + containerId);
        }
        try {
            Files.createDirectories(destDir.getParent());
            logger.raw().info("  Copying /deployment from container...");
            run("docker", "cp", containerId + ":/deployment", destDir.toString());
        } finally {
            new ProcessBuilder("docker", "rm", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Extracts the shim binary from the image using {@code docker create} + {@code docker cp}.
     */
    private static void extractShim(String imageTag, Path shimPath, OpenDstLogger logger)
            throws IOException, InterruptedException {
        logger.raw().info("  Extracting shim binary...");
        var createProc = new ProcessBuilder("docker", "create", imageTag)
                .redirectErrorStream(true)
                .start();
        var containerId = new String(createProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (createProc.waitFor() != 0) {
            throw new IOException("docker create failed for shim extraction");
        }
        try {
            run("docker", "cp", containerId + ":" + SHIM_PATH_IN_IMAGE, shimPath.toString());
            shimPath.toFile().setExecutable(true);
        } finally {
            new ProcessBuilder("docker", "rm", containerId)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Writes a Firecracker vmconfig.json with two virtio-blk drives:
     * <ul>
     *   <li>{@code rootfs} — the base OS image (read-write CoW, root device)</li>
     *   <li>{@code deployment} — the per-run deployment ext2 image (read-only, {@code /dev/vdb})</li>
     * </ul>
     */
    private static void writeVmConfig(Path rootfsPath, Path deploymentExt4Path, Path vmConfigPath)
            throws IOException {
        var kernelPath = System.getenv("NYX_KERNEL") != null ? System.getenv("NYX_KERNEL") : DEFAULT_KERNEL;

        // Validate kernel exists
        if (!Path.of(kernelPath).toFile().exists()) {
            throw new IOException(
                    "Kernel not found at: " + kernelPath + ". Set NYX_KERNEL env var to the vmlinux path.");
        }

        var config = """
                {
                  "boot-source": {
                    "kernel_image_path": "%s",
                    "boot_args": "ro console=ttyS0 noapic reboot=k panic=1 pci=off nomodules random.trust_cpu=on"
                  },
                  "drives": [
                    {
                      "drive_id": "rootfs",
                      "path_on_host": "%s",
                      "is_root_device": true,
                      "is_read_only": false,
                      "io_engine": "Cow"
                    },
                    {
                      "drive_id": "deployment",
                      "path_on_host": "%s",
                      "is_root_device": false,
                      "is_read_only": true,
                      "io_engine": "Cow"
                    }
                  ],
                  "network-interfaces": [],
                  "machine-config": {
                    "vcpu_count": 1,
                    "mem_size_mib": 1024
                  }
                }
                """.formatted(kernelPath, rootfsPath.toAbsolutePath(), deploymentExt4Path.toAbsolutePath());

        Files.writeString(vmConfigPath, config);
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        var proc = new ProcessBuilder(cmd).inheritIO().start();
        int code = proc.waitFor();
        if (code != 0) {
            throw new IOException("Command failed (exit " + code + "): " + String.join(" ", cmd));
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
