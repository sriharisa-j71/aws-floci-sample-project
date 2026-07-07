FROM docker.io/library/amazoncorretto:11-alpine

ARG SPARK_VERSION=3.3.0
ARG HADOOP_SHORT=3

RUN apk add --no-cache bash wget ca-certificates

RUN wget -qO- "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop${HADOOP_SHORT}.tgz" \
    | tar xz -C /opt && \
    ln -s "/opt/spark-${SPARK_VERSION}-bin-hadoop${HADOOP_SHORT}" /opt/spark

ARG HADOOP_AWS_VERSION=3.3.4
ARG AWS_SDK_BUNDLE_VERSION=1.12.367
RUN wget -q "https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/${HADOOP_AWS_VERSION}/hadoop-aws-${HADOOP_AWS_VERSION}.jar" \
    -O "/opt/spark/jars/hadoop-aws-${HADOOP_AWS_VERSION}.jar" && \
    wget -q "https://repo1.maven.org/maven2/com/amazonaws/aws-java-sdk-bundle/${AWS_SDK_BUNDLE_VERSION}/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar" \
    -O "/opt/spark/jars/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar"

RUN rm -rf /opt/spark/examples /opt/spark/data && \
    rm -f "$JAVA_HOME"/bin/javac "$JAVA_HOME"/bin/javadoc "$JAVA_HOME"/bin/javap \
      "$JAVA_HOME"/bin/jcmd "$JAVA_HOME"/bin/jconsole "$JAVA_HOME"/bin/jdb \
      "$JAVA_HOME"/bin/jdeprscan "$JAVA_HOME"/bin/jdeps "$JAVA_HOME"/bin/jfr \
      "$JAVA_HOME"/bin/jfrconv "$JAVA_HOME"/bin/jhsdb "$JAVA_HOME"/bin/jimage \
      "$JAVA_HOME"/bin/jinfo "$JAVA_HOME"/bin/jlink "$JAVA_HOME"/bin/jmap \
      "$JAVA_HOME"/bin/jmod "$JAVA_HOME"/bin/jpackage "$JAVA_HOME"/bin/jps \
      "$JAVA_HOME"/bin/jrunscript "$JAVA_HOME"/bin/jshell "$JAVA_HOME"/bin/jstack \
      "$JAVA_HOME"/bin/jstat "$JAVA_HOME"/bin/jstatd "$JAVA_HOME"/bin/serialver \
      "$JAVA_HOME"/bin/jarsigner "$JAVA_HOME"/bin/asprof && \
    rm -rf "$JAVA_HOME"/jmods "$JAVA_HOME"/lib/src.zip

ENV JAVA_HOME=/usr/lib/jvm/default-jvm \
    SPARK_HOME=/opt/spark \
    PATH=/opt/spark/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN adduser -D hadoop
USER hadoop
WORKDIR /home/hadoop
