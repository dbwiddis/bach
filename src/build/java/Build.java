/*
 * Bach - Java Shell Builder
 * Copyright (C) 2017 Christian Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

interface Build {

  Path TOOLS = Paths.get(".bach", "tools");
  Path SOURCE_MAIN = Paths.get("src", "main", "java");
  Path SOURCE_TEST = Paths.get("src", "test", "java");
  Path TARGET = Paths.get("target", "build");
  Path TARGET_MAIN = TARGET.resolve("classes/main");
  Path TARGET_TEST = TARGET.resolve("classes/test");
  Path JAVADOC = TARGET.resolve("javadoc");
  Path ARTIFACTS = TARGET.resolve("artifacts");

  String JUNIT_JUPITER_VERSION = "5.0.0-RC2";
  String JUNIT_PLATFORM_VERSION = "1.0.0-RC2";
  String OPENTEST4J_VERSION = "1.0.0-RC1";

  static void main(String... args) {
    try {
      format();
      clean();
      generate();
      compile();
      test();
      javadoc();
      jar();
      jdeps();
    } catch (Throwable throwable) {
      System.err.println("build failed due to: " + throwable);
      throwable.printStackTrace();
      System.exit(1);
    }
  }

  static void format() throws IOException {
    System.out.printf("%n[format]%n%n");

    String mode = Boolean.getBoolean("bach.format.replace") ? "replace" : "validate";
    String repo = "https://jitpack.io";
    String user = "com/github/sormuras";
    String name = "google-java-format";
    String version = "validate-SNAPSHOT";
    String file = name + "-" + version + "-all-deps.jar";
    URI uri = URI.create(String.join("/", repo, user, name, name, version, file));
    Path jar = JdkUtil.download(uri, TOOLS.resolve(name));
    JdkTool.Command format = JdkTool.command("java");
    format.add("-jar");
    format.add(jar);
    format.add("--" + mode);
    format.mark(10);
    List<Path> roots = List.of(Paths.get("src"), Paths.get("demo"));
    format.addAll(roots, unit -> JdkUtil.isJavaFile(unit) && !unit.endsWith("module-info.java"));
    format.dump(System.out::println);
    format.execute();
  }

  static void clean() throws IOException {
    System.out.printf("%n[clean]%n%n");

    JdkUtil.treeDelete(TARGET);
    System.out.println("deleted " + TARGET);
  }

  static void generate() throws IOException {
    System.out.printf("%n[generate]%n%n");

    Set<String> imports = new TreeSet<>();
    List<String> generated = new ArrayList<>();
    generated.add("/* THIS FILE IS GENERATED -- " + Instant.now() + " */");
    generated.add("/*");
    generated.add(" * Bach - Java Shell Builder");
    generated.add(" * Copyright (C) 2017 Christian Stein");
    generated.add(" *");
    generated.add(" * Licensed under the Apache License, Version 2.0 (the \"License\");");
    generated.add(" * you may not use this file except in compliance with the License.");
    generated.add(" * You may obtain a copy of the License at");
    generated.add(" *");
    generated.add(" *     https://www.apache.org/licenses/LICENSE-2.0");
    generated.add(" *");
    generated.add(" * Unless required by applicable law or agreed to in writing, software");
    generated.add(" * distributed under the License is distributed on an \"AS IS\" BASIS,");
    generated.add(" * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.");
    generated.add(" * See the License for the specific language governing permissions and");
    generated.add(" * limitations under the License.");
    generated.add(" */");
    generated.add("");
    generated.add("// default package");
    generated.add("");
    int indexOfImports = generated.size();
    generated.add("");
    generate(generated, SOURCE_MAIN.resolve("JdkTool.java"), imports);
    generated.add("");
    generate(generated, SOURCE_MAIN.resolve("JdkUtil.java"), imports);
    generated.add("");
    generate(generated, SOURCE_MAIN.resolve("Bach.java"), imports);
    generated.addAll(indexOfImports, imports);

    // write generated lines to temporary file
    Path generatedPath = TARGET.resolve("Bach.java");
    Files.createDirectories(TARGET);
    Files.deleteIfExists(generatedPath);
    Files.write(generatedPath, generated);
    System.out.println("generated " + generatedPath);

    // only copy if content changed - ignoring initial line, which contains the generation date
    Path publishedPath = Paths.get("Bach.java");
    List<String> published = Files.readAllLines(publishedPath);
    published.set(0, "");
    generated.set(0, "");
    int publishedHash = published.hashCode();
    int temporaryHash = generated.hashCode();
    System.out.println("generated hash code is 0x" + Integer.toHexString(temporaryHash));
    System.out.println("published hash code is 0x" + Integer.toHexString(publishedHash));
    if (publishedHash != temporaryHash) {
      Files.copy(generatedPath, publishedPath, StandardCopyOption.REPLACE_EXISTING);
      System.err.println("copied new Bach.java version - don't forget to publish (commit/push)");
    }
    System.out.flush();
    System.err.flush();
  }

  static void generate(List<String> target, Path source, Set<String> imports) throws IOException {
    List<String> lines = Files.readAllLines(source);
    boolean head = true;
    for (String line : lines) {
      if (head) {
        if (line.startsWith("import")) {
          imports.add(line);
        }
        if (line.equals("// Bach.java")) {
          head = false;
        }
        continue;
      }
      target.add(line);
    }
  }

  static void compile() throws IOException {
    System.out.printf("%n[compile]%n%n");

    // main
    JdkTool.Javac javac = new JdkTool.Javac();
    javac.generateAllDebuggingInformation = true;
    javac.destinationPath = TARGET_MAIN;
    javac.toCommand().add(TARGET.resolve("Bach.java")).dump(System.out::println).execute();

    // test
    javac.destinationPath = TARGET_TEST;
    javac.classPath =
        List.of(
            TARGET_MAIN,
            JdkUtil.resolve("org.junit.jupiter", "junit-jupiter-api", JUNIT_JUPITER_VERSION),
            JdkUtil.resolve("org.junit.platform", "junit-platform-commons", JUNIT_PLATFORM_VERSION),
            JdkUtil.resolve("org.opentest4j", "opentest4j", OPENTEST4J_VERSION));
    javac.toCommand().addAll(SOURCE_TEST, JdkUtil::isJavaFile).dump(System.out::println).execute();
  }

  static void javadoc() throws IOException {
    System.out.printf("%n[javadoc]%n%n");

    Files.createDirectories(JAVADOC);
    JdkTool.execute(
        "javadoc",
        "-quiet",
        "-Xdoclint:all,-missing",
        "-package",
        "-linksource",
        "-link",
        "http://download.java.net/java/jdk9/docs/api",
        "-d",
        JAVADOC,
        Paths.get("Bach.java"));
  }

  static void jar() throws IOException {
    System.out.printf("%n[jar]%n%n");

    Files.createDirectories(ARTIFACTS);
    jar("bach.jar", TARGET_MAIN, ".");
    jar("bach-sources.jar", SOURCE_MAIN, ".");
    jar("bach-javadoc.jar", JAVADOC, ".");
  }

  private static void jar(String artifact, Path path, Object... contents) {
    JdkTool.Jar jar = new JdkTool.Jar();
    jar.file = ARTIFACTS.resolve(artifact);
    jar.path = path;
    JdkTool.Command command = jar.toCommand();
    command.mark(5);
    Arrays.stream(contents).forEach(command::add);
    command.dump(System.out::println);
    command.execute();
  }

  static void jdeps() throws IOException {
    System.out.printf("%n[jdeps]%n%n");

    JdkTool.Jdeps jdeps = new JdkTool.Jdeps();
    jdeps.summary = true;
    jdeps.recursive = true;
    jdeps.toCommand().add(ARTIFACTS.resolve("bach.jar")).execute();
  }

  static void test() throws IOException {
    System.out.printf("%n[test]%n%n");

    String repo = "http://repo1.maven.org/maven2";
    String user = "org/junit/platform";
    String name = "junit-platform-console-standalone";
    String file = name + "-" + JUNIT_PLATFORM_VERSION + ".jar";
    URI uri = URI.create(String.join("/", repo, user, name, JUNIT_PLATFORM_VERSION, file));
    Path jar = JdkUtil.download(uri, TOOLS.resolve(name), file, p -> true);
    JdkTool.execute(
        "java",
        "-ea",
        "-jar",
        jar,
        "--class-path",
        TARGET_TEST,
        "--class-path",
        TARGET_MAIN,
        "--scan-classpath");
  }
}