#!/usr/bin/env bash
# Runs the dependency-free engine + room tests (no Maven needed, JDK 21 only).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -d out $(find src -path '*com/poker/engine*' -name '*.java' -o -path '*com/poker/game*' -name '*.java')
java -cp out com.poker.engine.EngineSelfTest
java -cp out com.poker.game.RoomSelfTest
