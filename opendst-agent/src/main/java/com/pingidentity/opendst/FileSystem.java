/*
 * Copyright 2025-2026 Ping Identity Corporation
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
package com.pingidentity.opendst;

import static com.pingidentity.opendst.Node.currentNodeOrNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * Functional module for file system simulation and instrumentation.
 */
@Intercepts("java.nio.file.Files")
public final class FileSystem {

    @Intercepts(value = "java.io.File", noOp = true, comment = "Redirection removed, using original File behavior.")
    private static final class FileMetadata {}

    @Intercepts("java.io.FileInputStream")
    private static final class FileInputStreamMetadata {}

    @Intercepts("java.io.FileOutputStream")
    private static final class FileOutputStreamMetadata {}

    @Intercepts("java.io.RandomAccessFile")
    private static final class RandomAccessFileMetadata {}

    private static void onFileSystemOp() throws IOException {
        var node = currentNodeOrNull();
        if (node != null) {
            node.faultInjector().onFileSystemOp();
        }
    }

    /** Deterministic implementation of {@link Files#newInputStream(Path, OpenOption...)}. */
    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.newInputStream(path, options);
    }

    /** Deterministic implementation of {@link Files#newOutputStream(Path, OpenOption...)}. */
    public static OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.newOutputStream(path, options);
    }

    /** Deterministic implementation of {@link Files#newBufferedReader(Path, Charset)}. */
    public static BufferedReader newBufferedReader(Path path, Charset cs) throws IOException {
        onFileSystemOp();
        return Files.newBufferedReader(path, cs);
    }

    /** Deterministic implementation of {@link Files#newBufferedReader(Path)}. */
    public static BufferedReader newBufferedReader(Path path) throws IOException {
        onFileSystemOp();
        return Files.newBufferedReader(path);
    }

    /** Deterministic implementation of {@link Files#newBufferedWriter(Path, Charset, OpenOption...)}. */
    public static BufferedWriter newBufferedWriter(Path path, Charset cs, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.newBufferedWriter(path, cs, options);
    }

    /** Deterministic implementation of {@link Files#newBufferedWriter(Path, OpenOption...)}. */
    public static BufferedWriter newBufferedWriter(Path path, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.newBufferedWriter(path, options);
    }

    /** Deterministic implementation of {@link Files#newByteChannel(Path, Set, FileAttribute...)}. */
    @SuppressWarnings("unchecked")
    public static SeekableByteChannel newByteChannel(
            Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.newByteChannel(path, options, attrs);
    }

    /** Deterministic implementation of {@link Files#newDirectoryStream(Path)}. */
    public static DirectoryStream<Path> newDirectoryStream(Path dir) throws IOException {
        onFileSystemOp();
        return Files.newDirectoryStream(dir);
    }

    /** Deterministic implementation of {@link Files#newDirectoryStream(Path, String)}. */
    public static DirectoryStream<Path> newDirectoryStream(Path dir, String filter) throws IOException {
        onFileSystemOp();
        return Files.newDirectoryStream(dir, filter);
    }

    /** Deterministic implementation of {@link Files#newDirectoryStream(Path, DirectoryStream.Filter)}. */
    public static DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        onFileSystemOp();
        return Files.newDirectoryStream(dir, filter);
    }

    /** Deterministic implementation of {@link Files#exists(Path, LinkOption...)}. */
    public static boolean exists(Path path, LinkOption... options) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.exists(path, options);
    }

    /** Deterministic implementation of {@link Files#notExists(Path, LinkOption...)}. */
    public static boolean notExists(Path path, LinkOption... options) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return true;
        }
        return Files.notExists(path, options);
    }

    /** Deterministic implementation of {@link Files#isDirectory(Path, LinkOption...)}. */
    public static boolean isDirectory(Path path, LinkOption... options) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.isDirectory(path, options);
    }

    /** Deterministic implementation of {@link Files#isRegularFile(Path, LinkOption...)}. */
    public static boolean isRegularFile(Path path, LinkOption... options) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.isRegularFile(path, options);
    }

    /** Deterministic implementation of {@link Files#isSymbolicLink(Path)}. */
    public static boolean isSymbolicLink(Path path) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.isSymbolicLink(path);
    }

    /** Deterministic implementation of {@link Files#isReadable(Path)}. */
    public static boolean isReadable(Path path) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.isReadable(path);
    }

    /** Deterministic implementation of {@link Files#isWritable(Path)}. */
    public static boolean isWritable(Path path) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.isWritable(path);
    }

    /** Deterministic implementation of {@link Files#isExecutable(Path)}. */
    public static boolean isExecutable(Path path) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return false;
        }
        return Files.isExecutable(path);
    }

    /** Deterministic implementation of {@link Files#isHidden(Path)}. */
    public static boolean isHidden(Path path) throws IOException {
        onFileSystemOp();
        return Files.isHidden(path);
    }

    /** Deterministic implementation of {@link Files#getAttribute(Path, String, LinkOption...)}. */
    public static Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
        onFileSystemOp();
        return Files.getAttribute(path, attribute, options);
    }

    /** Deterministic implementation of {@link Files#setAttribute(Path, String, Object, LinkOption...)}. */
    public static Path setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.setAttribute(path, attribute, value, options);
    }

    /** Deterministic implementation of {@link Files#getOwner(Path, LinkOption...)}. */
    public static UserPrincipal getOwner(Path path, LinkOption... options) throws IOException {
        onFileSystemOp();
        return Files.getOwner(path, options);
    }

    /** Deterministic implementation of {@link Files#setOwner(Path, UserPrincipal)}. */
    public static Path setOwner(Path path, UserPrincipal owner) throws IOException {
        onFileSystemOp();
        return Files.setOwner(path, owner);
    }

    /** Deterministic implementation of {@link Files#getLastModifiedTime(Path, LinkOption...)}. */
    public static FileTime getLastModifiedTime(Path path, LinkOption... options) throws IOException {
        onFileSystemOp();
        return Files.getLastModifiedTime(path, options);
    }

    /** Deterministic implementation of {@link Files#setLastModifiedTime(Path, FileTime)}. */
    public static Path setLastModifiedTime(Path path, FileTime time) throws IOException {
        onFileSystemOp();
        return Files.setLastModifiedTime(path, time);
    }

    /** Deterministic implementation of {@link Files#size(Path)}. */
    public static long size(Path path) throws IOException {
        onFileSystemOp();
        return Files.size(path);
    }

    /** Deterministic implementation of {@link Files#readAttributes(Path, Class, LinkOption...)}. */
    public static <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.readAttributes(path, type, options);
    }

    /** Deterministic implementation of {@link Files#readAttributes(Path, String, LinkOption...)}. */
    public static Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.readAttributes(path, attributes, options);
    }

    /** Deterministic implementation of {@link Files#createFile(Path, FileAttribute...)}. */
    public static Path createFile(Path path, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createFile(path, attrs);
    }

    /** Deterministic implementation of {@link Files#createDirectory(Path, FileAttribute...)}. */
    public static Path createDirectory(Path path, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createDirectory(path, attrs);
    }

    /** Deterministic implementation of {@link Files#createDirectory(Path, FileAttribute...)}. */
    public static Path createDirectories(Path path, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createDirectories(path, attrs);
    }

    /** Deterministic implementation of {@link Files#delete(Path)}. */
    public static void delete(Path path) throws IOException {
        onFileSystemOp();
        Files.delete(path);
    }

    /** Deterministic implementation of {@link Files#deleteIfExists(Path)}. */
    public static boolean deleteIfExists(Path path) throws IOException {
        onFileSystemOp();
        return Files.deleteIfExists(path);
    }

    /** Deterministic implementation of {@link Files#copy(Path, Path, CopyOption...)}. */
    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        onFileSystemOp();
        return Files.copy(source, target, options);
    }

    /** Deterministic implementation of {@link Files#move(Path, Path, CopyOption...)}. */
    public static Path move(Path source, Path target, CopyOption... options) throws IOException {
        onFileSystemOp();
        return Files.move(source, target, options);
    }

    /** Deterministic implementation of {@link Files#readAllBytes(Path)}. */
    public static byte[] readAllBytes(Path path) throws IOException {
        onFileSystemOp();
        return Files.readAllBytes(path);
    }

    /** Deterministic implementation of {@link Files#write(Path, byte[], OpenOption...)}. */
    public static Path write(Path path, byte[] bytes, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.write(path, bytes, options);
    }

    /** Deterministic implementation of {@link Files#write(Path, Iterable, OpenOption...)}. */
    public static Path write(Path path, Iterable<? extends CharSequence> lines, OpenOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.write(path, lines, options);
    }

    /** Deterministic implementation of {@link Files#write(Path, Iterable, Charset, OpenOption...)}. */
    public static Path write(Path path, Iterable<? extends CharSequence> lines, Charset cs, OpenOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.write(path, lines, cs, options);
    }

    /** Deterministic implementation of {@link Files#readAllLines(Path)}. */
    public static List<String> readAllLines(Path path) throws IOException {
        onFileSystemOp();
        return Files.readAllLines(path);
    }

    /** Deterministic implementation of {@link Files#readAllLines(Path, Charset)}. */
    public static List<String> readAllLines(Path path, Charset cs) throws IOException {
        onFileSystemOp();
        return Files.readAllLines(path, cs);
    }

    /** Deterministic implementation of {@link Files#walkFileTree(Path, Set, int, FileVisitor)}. */
    public static Path walkFileTree(
            Path start, Set<FileVisitOption> options, int maxDepth, FileVisitor<? super Path> visitor)
            throws IOException {
        onFileSystemOp();
        return Files.walkFileTree(start, options, maxDepth, visitor);
    }

    /** Deterministic implementation of {@link Files#walkFileTree(Path, FileVisitor)}. */
    public static Path walkFileTree(Path start, FileVisitor<? super Path> visitor) throws IOException {
        onFileSystemOp();
        return Files.walkFileTree(start, visitor);
    }

    /** Deterministic implementation of {@link Files#newByteChannel(Path, OpenOption...)}. */
    public static SeekableByteChannel newByteChannel(Path path, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.newByteChannel(path, options);
    }

    /** Deterministic implementation of {@link Files#isSameFile(Path, Path)}. */
    public static boolean isSameFile(Path path, Path path2) throws IOException {
        onFileSystemOp();
        return Files.isSameFile(path, path2);
    }

    /** Deterministic implementation of {@link Files#getFileStore(Path)}. */
    public static FileStore getFileStore(Path path) throws IOException {
        onFileSystemOp();
        return Files.getFileStore(path);
    }

    /** Deterministic implementation of {@link Files#probeContentType(Path)}. */
    public static String probeContentType(Path path) throws IOException {
        onFileSystemOp();
        return Files.probeContentType(path);
    }

    /** Deterministic implementation of {@link Files#readSymbolicLink(Path)}. */
    public static Path readSymbolicLink(Path link) throws IOException {
        onFileSystemOp();
        return Files.readSymbolicLink(link);
    }

    /** Deterministic implementation of {@link Files#readString(Path)}. */
    public static String readString(Path path) throws IOException {
        onFileSystemOp();
        return Files.readString(path);
    }

    /** Deterministic implementation of {@link Files#readString(Path, Charset)}. */
    public static String readString(Path path, Charset cs) throws IOException {
        onFileSystemOp();
        return Files.readString(path, cs);
    }

    /** Deterministic implementation of {@link Files#writeString(Path, CharSequence, OpenOption...)}. */
    public static Path writeString(Path path, CharSequence csq, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.writeString(path, csq, options);
    }

    /** Deterministic implementation of {@link Files#writeString(Path, CharSequence, Charset, OpenOption...)}. */
    public static Path writeString(Path path, CharSequence csq, Charset cs, OpenOption... options) throws IOException {
        onFileSystemOp();
        return Files.writeString(path, csq, cs, options);
    }

    /** Deterministic implementation of {@link Files#getFileAttributeView(Path, Class, LinkOption...)}. */
    public static <V extends FileAttributeView> V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            return null;
        }
        return Files.getFileAttributeView(path, type, options);
    }

    /** Deterministic implementation of {@link Files#list(Path)}. */
    public static Stream<Path> list(Path dir) throws IOException {
        onFileSystemOp();
        return Files.list(dir);
    }

    /** Deterministic implementation of {@link Files#walk(Path, int, FileVisitOption...)}. */
    public static Stream<Path> walk(Path start, int maxDepth, FileVisitOption... options) throws IOException {
        onFileSystemOp();
        return Files.walk(start, maxDepth, options);
    }

    /** Deterministic implementation of {@link Files#walk(Path, FileVisitOption...)}. */
    public static Stream<Path> walk(Path start, FileVisitOption... options) throws IOException {
        onFileSystemOp();
        return Files.walk(start, options);
    }

    /** Deterministic implementation of {@link Files#find(Path, int, BiPredicate, FileVisitOption...)}. */
    public static Stream<Path> find(
            Path start, int maxDepth, BiPredicate<Path, BasicFileAttributes> matcher, FileVisitOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.find(start, maxDepth, matcher, options);
    }

    /** Deterministic implementation of {@link Files#lines(Path)}. */
    public static Stream<String> lines(Path path) throws IOException {
        onFileSystemOp();
        return Files.lines(path);
    }

    /** Deterministic implementation of {@link Files#lines(Path, Charset)}. */
    public static Stream<String> lines(Path path, Charset cs) throws IOException {
        onFileSystemOp();
        return Files.lines(path, cs);
    }

    /** Deterministic implementation of {@link Files#copy(Path, OutputStream)}. */
    public static long copy(Path source, OutputStream out) throws IOException {
        onFileSystemOp();
        return Files.copy(source, out);
    }

    /** Deterministic implementation of {@link Files#copy(InputStream, Path, CopyOption...)}. */
    public static long copy(InputStream in, Path target, CopyOption... options) throws IOException {
        onFileSystemOp();
        return Files.copy(in, target, options);
    }

    /** Deterministic implementation of {@link Files#mismatch(Path, Path)}. */
    public static long mismatch(Path path, Path path2) throws IOException {
        onFileSystemOp();
        return Files.mismatch(path, path2);
    }

    /** Deterministic implementation of {@link Files#createSymbolicLink(Path, Path, FileAttribute...)}. */
    public static Path createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createSymbolicLink(link, target, attrs);
    }

    /** Deterministic implementation of {@link Files#createLink(Path, Path)}. */
    public static Path createLink(Path link, Path existing) throws IOException {
        onFileSystemOp();
        return Files.createLink(link, existing);
    }

    /** Deterministic implementation of {@link Files#getPosixFilePermissions(Path, LinkOption...)}. */
    public static Set<PosixFilePermission> getPosixFilePermissions(Path path, LinkOption... options)
            throws IOException {
        onFileSystemOp();
        return Files.getPosixFilePermissions(path, options);
    }

    /** Deterministic implementation of {@link Files#setPosixFilePermissions(Path, Set)}. */
    public static Path setPosixFilePermissions(Path path, Set<PosixFilePermission> perms) throws IOException {
        onFileSystemOp();
        return Files.setPosixFilePermissions(path, perms);
    }

    /** Deterministic implementation of {@link Files#createTempFile(Path, String, String, FileAttribute...)}. */
    public static Path createTempFile(Path dir, String prefix, String suffix, FileAttribute<?>... attrs)
            throws IOException {
        onFileSystemOp();
        return Files.createTempFile(dir, prefix, suffix, attrs);
    }

    /** Deterministic implementation of {@link Files#createTempFile(String, String, FileAttribute...)}. */
    public static Path createTempFile(String prefix, String suffix, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createTempFile(prefix, suffix, attrs);
    }

    /** Deterministic implementation of {@link Files#createTempDirectory(Path, String, FileAttribute...)}. */
    public static Path createTempDirectory(Path dir, String prefix, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createTempDirectory(dir, prefix, attrs);
    }

    /** Deterministic implementation of {@link Files#createTempDirectory(String, FileAttribute...)}. */
    public static Path createTempDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
        onFileSystemOp();
        return Files.createTempDirectory(prefix, attrs);
    }

    /** Deterministic implementation of {@link FileInputStream#FileInputStream(String)}. */
    public static FileInputStream newFileInputStream(String name) throws IOException {
        onFileSystemOp();
        return new FileInputStream(name);
    }

    /** Deterministic implementation of {@link FileInputStream#FileInputStream(File)}. */
    public static FileInputStream newFileInputStream(File file) throws IOException {
        onFileSystemOp();
        return new FileInputStream(file);
    }

    /** Deterministic implementation of {@link FileInputStream#FileInputStream(FileDescriptor)}. */
    public static FileInputStream newFileInputStream(FileDescriptor fdObj) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            // Should not happen for FileDescriptor based init, but for consistency...
        }
        return new FileInputStream(fdObj);
    }

    /** Deterministic implementation of {@link FileOutputStream#FileOutputStream(String)}. */
    public static FileOutputStream newFileOutputStream(String name) throws IOException {
        onFileSystemOp();
        return new FileOutputStream(name);
    }

    /** Deterministic implementation of {@link FileOutputStream#FileOutputStream(String, boolean)}. */
    public static FileOutputStream newFileOutputStream(String name, boolean append) throws IOException {
        onFileSystemOp();
        return new FileOutputStream(name, append);
    }

    /** Deterministic implementation of {@link FileOutputStream#FileOutputStream(File)}. */
    public static FileOutputStream newFileOutputStream(File file) throws IOException {
        onFileSystemOp();
        return new FileOutputStream(file);
    }

    /** Deterministic implementation of {@link FileOutputStream#FileOutputStream(File, boolean)}. */
    public static FileOutputStream newFileOutputStream(File file, boolean append) throws IOException {
        onFileSystemOp();
        return new FileOutputStream(file, append);
    }

    /** Deterministic implementation of {@link FileOutputStream#FileOutputStream(FileDescriptor)}. */
    public static FileOutputStream newFileOutputStream(FileDescriptor fdObj) {
        try {
            onFileSystemOp();
        } catch (IOException e) {
            // Consistency
        }
        return new FileOutputStream(fdObj);
    }

    /** Deterministic implementation of {@link RandomAccessFile#RandomAccessFile(String, String)}. */
    public static RandomAccessFile newRandomAccessFile(String name, String mode) throws IOException {
        onFileSystemOp();
        return new RandomAccessFile(name, mode);
    }

    /** Deterministic implementation of {@link RandomAccessFile#RandomAccessFile(File, String)}. */
    public static RandomAccessFile newRandomAccessFile(File file, String mode) throws IOException {
        onFileSystemOp();
        return new RandomAccessFile(file, mode);
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent;
    }

    private FileSystem() {
        // Prevent instantiation
    }
}
