#!/usr/bin/env bash
link=${1-"localhost"}
host=$(basename $(dirname $link))
host=${host%%:*}
port=${2-"10001"}
sbt "project noah" --error "run-main edu.uci.ics.cloudberry.noah.feed.FileFeedDriver \
-u 127.0.0.1 -p $port"
