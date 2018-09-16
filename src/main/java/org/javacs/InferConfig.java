package org.javacs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");

    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;
    /** External dependencies specified manually by the user */
    private final Collection<String> externalDependencies;
    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;
    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenHome, Path gradleHome) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome());
    }

    InferConfig(Path workspaceRoot) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome());
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    Set<Path> classPath() {
        var result = new HashSet<Path>();
        result.addAll(buildClassPath());
        result.addAll(workspaceClassPath());
        return result;
    }

    /** Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars in bazel-genfiles */
    Set<Path> buildClassPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, false);
                if (found.isPresent()) result.add(found.get());
                else LOG.warning(String.format("Couldn't find jar for %s in %s or %s", a, mavenHome, gradleHome));
            }
            return result;
        }

        // Maven
        if (Files.exists(workspaceRoot.resolve("pom.xml"))) {
            var result = new HashSet<Path>();
            for (var a : mvnDependencies()) {
                var found = findMavenJar(a, false);
                if (found.isPresent()) result.add(found.get());
                else LOG.warning(String.format("Couldn't find jar for %s in %s", a, mavenHome));
            }
            return result;
        }

        // Bazel
        if (Files.exists(workspaceRoot.resolve("WORKSPACE"))) {
            var result = new HashSet<Path>();
            var bazelGenFiles = workspaceRoot.resolve("bazel-genfiles");

            if (Files.exists(bazelGenFiles) && Files.isSymbolicLink(bazelGenFiles)) {
                LOG.info("Looking for bazel generated files in " + bazelGenFiles);
                var jars = bazelJars(bazelGenFiles);
                LOG.info(String.format("Found %d generated-files directories", jars.size()));
                result.addAll(jars);
            }
            return result;
        }

        return Collections.emptySet();
    }

    /**
     * Find directories that contain java .class files in the workspace, for example files generated by maven in
     * target/classes
     */
    Set<Path> workspaceClassPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            return Collections.emptySet();
        }

        // Maven
        if (Files.exists(workspaceRoot.resolve("pom.xml"))) {
            try {
                return Files.walk(workspaceRoot).flatMap(this::outputDirectory).collect(Collectors.toSet());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // Bazel
        if (Files.exists(workspaceRoot.resolve("WORKSPACE"))) {
            var bazelBin = workspaceRoot.resolve("bazel-bin");

            if (Files.exists(bazelBin) && Files.isSymbolicLink(bazelBin)) {
                return bazelOutputDirectories(bazelBin);
            }
        }

        return Collections.emptySet();
    }

    /** Recognize build root files like pom.xml and return compiler output directories */
    private Stream<Path> outputDirectory(Path file) {
        if (file.getFileName().toString().equals("pom.xml")) {
            var target = file.resolveSibling("target");

            if (Files.exists(target) && Files.isDirectory(target)) {
                return Stream.of(target.resolve("classes"), target.resolve("test-classes"));
            }
        }

        // TODO gradle

        return Stream.empty();
    }

    private void findBazelJavac(File bazelRoot, File workspaceRoot, Set<Path> acc) {
        // If _javac directory exists, search it for dirs with names like lib*_classes
        var javac = new File(bazelRoot, "_javac");
        if (javac.exists()) {
            var match = FileSystems.getDefault().getPathMatcher("glob:**/lib*_classes");
            try {
                Files.walk(javac.toPath()).filter(match::matches).filter(Files::isDirectory).forEach(acc::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        // Recurse into all directories that mirror the structure of the workspace
        if (bazelRoot.isDirectory()) {
            var children = bazelRoot.list((__, name) -> new File(workspaceRoot, name).exists());
            for (var child : children) {
                var bazelChild = new File(bazelRoot, child);
                var workspaceChild = new File(workspaceRoot, child);
                findBazelJavac(bazelChild, workspaceChild, acc);
            }
        }
    }

    /**
     * Search bazel-bin for per-module output directories matching the pattern:
     *
     * <p>bazel-bin/path/to/module/_javac/rule/lib*_classes
     */
    private Set<Path> bazelOutputDirectories(Path bazelBin) {
        try {
            var bazelBinTarget = Files.readSymbolicLink(bazelBin);
            LOG.info("Searching for bazel output directories in " + bazelBinTarget);

            var dirs = new HashSet<Path>();
            findBazelJavac(bazelBinTarget.toFile(), workspaceRoot.toFile(), dirs);
            LOG.info(String.format("Found %d bazel output directories", dirs.size()));

            return dirs;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Search bazel-genfiles for jars */
    private Set<Path> bazelJars(Path bazelGenFiles) {
        try {
            var target = Files.readSymbolicLink(bazelGenFiles);

            return Files.walk(target)
                    .filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Find source .jar files in local maven repository. */
    Set<Path> buildDocPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, true);
                if (found.isPresent()) result.add(found.get());
                else LOG.warning(String.format("Couldn't find doc jar for %s in %s or %s", a, mavenHome, gradleHome));
            }
            return result;
        }

        // Maven
        if (Files.exists(workspaceRoot.resolve("pom.xml"))) {
            var result = new HashSet<Path>();
            for (var a : mvnDependencies()) {
                findMavenJar(a, true).ifPresent(result::add);
            }
            return result;
        }

        // TODO Bazel

        return Collections.emptySet();
    }

    private Optional<Path> findAnyJar(Artifact artifact, boolean source) {
        Optional<Path> maven = findMavenJar(artifact, source);

        if (maven.isPresent()) return maven;
        else return findGradleJar(artifact, source);
    }

    Optional<Path> findMavenJar(Artifact artifact, boolean source) {
        var jar =
                mavenHome
                        .resolve("repository")
                        .resolve(artifact.groupId.replace('.', File.separatorChar))
                        .resolve(artifact.artifactId)
                        .resolve(artifact.version)
                        .resolve(fileName(artifact, source));

        if (Files.exists(jar)) return Optional.of(jar);
        else return Optional.empty();
    }

    private Optional<Path> findGradleJar(Artifact artifact, boolean source) {
        // Search for caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        var base = gradleHome.resolve("caches");
        var pattern =
                "glob:"
                        + String.join(
                                File.separator,
                                base.toString(),
                                "modules-*",
                                "files-*",
                                artifact.groupId,
                                artifact.artifactId,
                                artifact.version,
                                "*",
                                fileName(artifact, source));
        var match = FileSystems.getDefault().getPathMatcher(pattern);

        try {
            return Files.walk(base, 7).filter(match::matches).findFirst();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String fileName(Artifact artifact, boolean source) {
        return artifact.artifactId + '-' + artifact.version + (source ? "-sources" : "") + ".jar";
    }

    static List<Artifact> dependencyList(Path pomXml) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");

        try {
            // Tell maven to output deps to a temporary file
            var outputFile = Files.createTempFile("deps", ".txt");

            var cmd =
                    String.format(
                            "%s dependency:list -DincludeScope=test -DoutputFile=%s", getMvnCommand(), outputFile);
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor();

            if (result != 0) throw new RuntimeException("`" + cmd + "` returned " + result);

            return readDependencyList(outputFile);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Artifact> readDependencyList(Path outputFile) {
        var artifact = Pattern.compile(".*:.*:.*:.*:.*");

        try (var in = Files.newInputStream(outputFile)) {
            return new BufferedReader(new InputStreamReader(in))
                    .lines()
                    .map(String::trim)
                    .filter(line -> artifact.matcher(line).matches())
                    .map(Artifact::parse)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Collection<Artifact> mvnDependencies() {
        var pomXml = workspaceRoot.resolve("pom.xml");

        if (Files.exists(pomXml)) return dependencyList(pomXml);

        return Collections.emptyList();
    }

    static String getMvnCommand() {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd");
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat");
            }
        }
        return mvnCommand;
    }

    private static String findExecutableOnPath(String name) {
        for (var dirname : System.getenv("PATH").split(File.pathSeparator)) {
            var file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }
}
