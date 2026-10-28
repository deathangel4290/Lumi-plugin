#!/usr/bin/env bash
# Builds paper-api 1.21.11 from source and installs it in ~/.m2, for machines that can't reach
# repo.papermc.io (cloud sessions) but can reach GitHub and Maven Central. Afterwards
# `mvn -B package` works as usual. Usage: tools/build-paper-api.sh [work-dir]
set -euo pipefail
WORK="${1:-${TMPDIR:-/tmp}/paper-api-build}"
mkdir -p "$WORK" && cd "$WORK"

# Brigadier is not on Maven Central: compile it from Mojang's source.
[ -d brigadier ] || git clone -q --depth 1 https://github.com/Mojang/brigadier brigadier
mkdir -p brigadier/out
javac -nowarn -d brigadier/out $(find brigadier/src/main/java -name "*.java")
jar cf brigadier/brigadier-1.3.10.jar -C brigadier/out .
mvn -B -q install:install-file -Dfile=brigadier/brigadier-1.3.10.jar -DgroupId=com.mojang -DartifactId=brigadier -Dversion=1.3.10 -Dpackaging=jar

# Paper's API sources for 1.21.11 only.
if [ ! -d paper ]; then
  git clone -q --depth 1 --branch ver/1.21.11 --filter=blob:none --sparse https://github.com/PaperMC/Paper paper
  (cd paper && git sparse-checkout set paper-api)
fi

mkdir -p api
cat > api/pom.xml <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.papermc.paper</groupId>
  <artifactId>paper-api</artifactId>
  <version>1.21.11-R0.1-SNAPSHOT</version>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <maven.test.skip>true</maven.test.skip>
  </properties>
  <dependencyManagement><dependencies>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-bom</artifactId><version>4.26.1</version><type>pom</type><scope>import</scope></dependency>
  </dependencies></dependencyManagement>
  <dependencies>
    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.3.1-jre</version></dependency>
    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>2.11.0</version></dependency>
    <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>2.2</version></dependency>
    <dependency><groupId>org.joml</groupId><artifactId>joml</artifactId><version>1.10.8</version></dependency>
    <dependency><groupId>it.unimi.dsi</groupId><artifactId>fastutil</artifactId><version>8.5.15</version></dependency>
    <dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-api</artifactId><version>2.24.1</version></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.16</version></dependency>
    <dependency><groupId>com.mojang</groupId><artifactId>brigadier</artifactId><version>1.3.10</version></dependency>
    <dependency><groupId>net.md-5</groupId><artifactId>bungeecord-chat</artifactId><version>1.21-R0.2</version>
      <exclusions><exclusion><groupId>com.google.guava</groupId><artifactId>guava</artifactId></exclusion></exclusions></dependency>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-api</artifactId></dependency>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-text-minimessage</artifactId></dependency>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-text-serializer-gson</artifactId></dependency>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-text-serializer-legacy</artifactId></dependency>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-text-serializer-plain</artifactId></dependency>
    <dependency><groupId>net.kyori</groupId><artifactId>adventure-text-logger-slf4j</artifactId></dependency>
    <dependency><groupId>org.apache.maven</groupId><artifactId>maven-resolver-provider</artifactId><version>3.9.6</version></dependency>
    <dependency><groupId>org.apache.maven.resolver</groupId><artifactId>maven-resolver-connector-basic</artifactId><version>1.9.18</version><scope>provided</scope></dependency>
    <dependency><groupId>org.apache.maven.resolver</groupId><artifactId>maven-resolver-transport-http</artifactId><version>1.9.18</version><scope>provided</scope></dependency>
    <dependency><groupId>org.jetbrains</groupId><artifactId>annotations</artifactId><version>26.0.2</version><scope>provided</scope></dependency>
    <dependency><groupId>org.checkerframework</groupId><artifactId>checker-qual</artifactId><version>3.49.2</version></dependency>
    <dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>1.0.0</version></dependency>
  </dependencies>
  <build>
    <sourceDirectory>$WORK/paper/paper-api/src/main/java</sourceDirectory>
    <resources><resource><directory>$WORK/paper/paper-api/src/main/resources</directory></resource></resources>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
        <configuration>
          <compilerArgs><arg>-proc:none</arg><arg>-nowarn</arg><arg>-Xmaxerrs</arg><arg>50</arg></compilerArgs>
          <additionalClasspathElements/>
        </configuration>
        <executions><execution><id>gen</id><phase>generate-sources</phase></execution></executions>
      </plugin>
      <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.0</version>
        <executions><execution><id>add-gen</id><phase>generate-sources</phase><goals><goal>add-source</goal></goals>
          <configuration><sources><source>$WORK/paper/paper-api/src/generated/java</source></sources></configuration></execution></executions>
      </plugin>
    </plugins>
  </build>
</project>
POM
(cd api && mvn -B -q install)
echo "paper-api 1.21.11-R0.1-SNAPSHOT installed in ~/.m2"
