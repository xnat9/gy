FROM docker.io/gradle:6.7
ENV TZ Asia/Chongqing

RUN mkdir -p /srv/gy

ADD bin /srv/gy/bin
ADD conf /srv/gy/conf
ADD src /srv/gy/src
ADD build.gradle /srv/gy/build.gradle
ADD start.sh /srv/gy/start.sh

WORKDIR /srv/gy/

RUN gradle deps

ENTRYPOINT exec sh start.sh $JAVA_OPTS