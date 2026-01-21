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
package com.pingidentity.opendst.maven;

import static java.nio.file.Files.exists;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.SelectorUtils;

/**
 * Service for discovering OpenDST tests and methods in a Maven project.
 */
final class TestDiscoverer {

    record TestRequest(String className, String methodName) {}

    private record PatternPair(String classPattern, String methodPattern) {
        static PatternPair from(String pattern) {
            int hashIdx = pattern.indexOf('#');
            if (hashIdx >= 0) {
                return new PatternPair(pattern.substring(0, hashIdx), pattern.substring(hashIdx + 1));
            } else {
                return new PatternPair(pattern, null);
            }
        }
    }

    private final MavenProject project;
    private final Path basePath;
    private final Log log;

    TestDiscoverer(MavenProject project, Path basePath, Log log) {
        this.project = project;
        this.basePath = basePath;
        this.log = log;
    }

    List<TestRequest> getTestsToRun(String test, List<String> includes, List<String> excludes)
            throws MojoFailureException {
        List<PatternPair> includePairs;
        if (test != null && !test.isBlank()) {
            includePairs = Arrays.stream(test.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(PatternPair::from)
                    .toList();
        } else if (includes != null && !includes.isEmpty()) {
            includePairs = includes.stream().map(PatternPair::from).toList();
        } else {
            includePairs = singletonList(new PatternPair("**/*DST", null));
        }
        var excludePairs = excludes != null && !excludes.isEmpty()
                ? excludes.stream().map(PatternPair::from).toList()
                : Collections.<PatternPair>emptyList();
        return discoverTests(includePairs, excludePairs);
    }

    private List<TestRequest> discoverTests(List<PatternPair> includePairs, List<PatternPair> excludePairs)
            throws MojoFailureException {
        var testClassesDir = basePath.resolve("target/test-classes");
        if (!exists(testClassesDir)) {
            return List.of();
        }

        var scanner = new DirectoryScanner();
        scanner.setBasedir(testClassesDir.toFile());
        scanner.setIncludes(includePairs.stream()
                .map(p -> normalizePattern(p.classPattern()))
                .toArray(String[]::new));
        if (!excludePairs.isEmpty()) {
            scanner.setExcludes(excludePairs.stream()
                    .map(p -> normalizePattern(p.classPattern()))
                    .toArray(String[]::new));
        }
        scanner.scan();

        var requests = new ArrayList<TestRequest>();
        try (var loader = getTestClassLoader()) {
            for (var file : scanner.getIncludedFiles()) {
                if (!file.endsWith(".class")) {
                    continue;
                }
                var className = file.substring(0, file.length() - 6).replace(File.separatorChar, '.');
                var methods = discoverMethods(loader, className, includePairs, excludePairs);
                for (var method : methods) {
                    requests.add(new TestRequest(className, method));
                }
            }
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoFailureException("Failed to discover tests", e);
        }
        return requests;
    }

    private List<String> discoverMethods(
            ClassLoader loader, String className, List<PatternPair> includePairs, List<PatternPair> excludePairs) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            log.warn("Could not load class " + className + " for method discovery");
            return emptyList();
        }

        var discoveredMethods = new ArrayList<String>();
        for (var method : clazz.getDeclaredMethods()) {
            if (isValidTestMethod(method)) {
                var methodName = method.getName();

                boolean included = false;
                for (var pair : includePairs) {
                    if (matchClass(className, pair.classPattern()) && matchMethod(methodName, pair.methodPattern())) {
                        included = true;
                        break;
                    }
                }

                if (!included) {
                    continue;
                }

                boolean excluded = false;
                for (var pair : excludePairs) {
                    if (matchClass(className, pair.classPattern()) && matchMethod(methodName, pair.methodPattern())) {
                        excluded = true;
                        break;
                    }
                }

                if (!excluded) {
                    discoveredMethods.add(methodName);
                }
            }
        }
        return discoveredMethods;
    }

    private boolean isValidTestMethod(Method method) {
        if (method.getDeclaringClass() == Object.class) {
            return false;
        }
        return Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0
                && method.getReturnType() == void.class;
    }

    private boolean matchClass(String className, String pattern) {
        var p = pattern;
        if (p.endsWith(".class")) {
            p = p.substring(0, p.length() - 6);
        }
        p = p.replace(File.separatorChar, '.').replace('/', '.');
        if (p.contains(".")) {
            return SelectorUtils.matchPath(p, className, ".", true);
        } else {
            return SelectorUtils.matchPath("**." + p, className, ".", true);
        }
    }

    private boolean matchMethod(String methodName, String pattern) {
        if (pattern == null || pattern.equals("*") || pattern.isEmpty()) {
            return true;
        }
        return SelectorUtils.match(pattern, methodName);
    }

    private URLClassLoader getTestClassLoader() throws DependencyResolutionRequiredException, MalformedURLException {
        var classpathElements = project.getTestClasspathElements();
        var urls = new URL[classpathElements.size()];
        for (int i = 0; i < classpathElements.size(); i++) {
            urls[i] = new File(classpathElements.get(i)).toURI().toURL();
        }
        return new URLClassLoader(urls, getClass().getClassLoader());
    }

    private String normalizePattern(String pattern) {
        var p = pattern;
        if (p.endsWith(".class")) {
            p = p.substring(0, p.length() - 6).replace('.', File.separatorChar) + ".class";
        } else {
            p = p.replace('.', File.separatorChar);
        }

        if (p.indexOf(File.separatorChar) < 0) {
            p = "**" + File.separatorChar + p;
        }

        if (!p.endsWith(".class") && !p.endsWith("*")) {
            p += ".class";
        }
        return p;
    }
}
