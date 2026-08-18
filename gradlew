#!/bin/sh
# Gradle start up script (POSIX)
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then
  JAVACMD=$JAVA_HOME/bin/java
else
  JAVACMD=java
fi
exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
