FROM public.ecr.aws/glue/aws-glue-libs:5 AS full

FROM amazonlinux:2023 AS tools

RUN dnf install -y \
    java-17-amazon-corretto-devel \
    shadow-utils procps \
    postgresql15 \
    unzip && \
    dnf clean all

# AWS CLI v2 (standalone binary, no Python needed)
RUN curl -sL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o /tmp/awscliv2.zip && \
    unzip -q /tmp/awscliv2.zip -d /tmp && \
    /tmp/aws/install && \
    rm -rf /tmp/aws*

FROM tools

COPY --from=full /usr/lib/spark/jars      /usr/lib/spark/jars
COPY --from=full /usr/lib/spark/bin       /usr/lib/spark/bin
COPY --from=full /usr/lib/spark/sbin      /usr/lib/spark/sbin
COPY --from=full /usr/lib/spark/conf      /usr/lib/spark/conf

COPY --from=full /usr/share/aws/glue-pds/jars /usr/share/aws/glue-pds/jars
COPY --from=full /usr/share/aws/aws-java-sdk-v2 /usr/share/aws/aws-java-sdk-v2
COPY --from=full /usr/share/aws/glue-streaming /usr/share/aws/glue-streaming

RUN rm -rf \
    /usr/share/aws/glue-pds/jars/snowflake-jdbc-*.jar \
    /usr/share/aws/glue-pds/jars/delta-*.jar \
    /usr/share/aws/glue-pds/jars/mssql-jdbc-*.jar \
    /usr/share/aws/glue-pds/jars/mysql-connector-*.jar \
    /usr/share/aws/glue-pds/jars/mongodb-*.jar \
    /usr/share/aws/glue-pds/jars/bson-*.jar \
    /usr/share/aws/glue-pds/jars/junit-*.jar \
    /usr/share/aws/glue-pds/jars/mockito-*.jar \
    /usr/share/aws/glue-pds/jars/powermock-*.jar \
    /usr/share/aws/glue-pds/jars/testng-*.jar \
    /usr/share/aws/glue-pds/jars/hamcrest-*.jar \
    /usr/share/aws/glue-pds/jars/lombok-*.jar \
    /usr/share/aws/glue-pds/jars/jquery-*.jar \
    /usr/share/aws/glue-pds/jars/cloudformation-*.jar \
    # /usr/share/aws/glue-pds/jars/cloudwatch-*.jar \  # kept — needed for Glue continuous logging on real AWS
    /usr/share/aws/glue-pds/jars/lakeformation-*.jar \
    /usr/share/aws/glue-pds/jars/kinesis-*.jar \
    /usr/share/aws/glue-pds/jars/dynamodb-*.jar \
    /usr/share/aws/glue-pds/jars/redshift-*.jar \
    /usr/share/aws/glue-pds/jars/secretsmanager-*.jar \
    /usr/share/aws/glue-pds/jars/iam-*.jar \
    /usr/share/aws/glue-pds/jars/emr-dynamodb-*.jar \
    /usr/share/aws/glue-pds/jars/msgpack-*.jar \
    /usr/share/aws/glue-pds/jars/argon2-*.jar \
    /usr/lib/spark/data

# hadoop-aws + AWS SDK v2 bundle needed on Spark's classpath for S3A
RUN cp /usr/share/aws/glue-pds/jars/hadoop-aws-*.jar /usr/lib/spark/jars/ && \
    cp /usr/share/aws/aws-java-sdk-v2/aws-sdk-java-bundle-*.jar /usr/lib/spark/jars/

ENV JAVA_HOME=/usr/lib/jvm/jre-17 \
    SPARK_HOME=/usr/lib/spark \
    SPARK_CONF_DIR=/etc/spark/conf \
    LANG=C.UTF-8

ENV PATH=/usr/lib/spark/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN useradd -m hadoop
USER hadoop
WORKDIR /home/hadoop
