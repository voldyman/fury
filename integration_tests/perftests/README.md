Module sued to proto/flatbuffers benchmark without pollute core codebase and serve as integration_tests to 
ensure java maven shade work and so on.
# Protobuffers/Flatbuffers benchmark
Generate code manually:
```bash
flatc  -o src/main/java -j  src/main/java/io/fury/integration_tests/state/bench.fbs 
protoc -I=src/main/java/io/fury/integration_tests/state --java_out=src/main/java/ bench.proto
```
proto code can be generated by maven plugin.
Flatbuffers generated is short, just add generated files to repo directly.

# Run tests
```bash
cd java && mvn -T10 install -DskipTests -Dcheckstyle.skip -Dlicense.skip -Dmaven.javadoc.skip && cd ../integration_tests/perftests
mvn -T10 compile
#mvn exec:java -Dexec.mainClass="io.fury.integration_tests.UserTypeSerializeSuite" -Dexec.args="-f 1 -wi 0 -i 1 -t 1 -w 1s -r 1s -rf csv"
mvn exec:java -Dexec.mainClass="io.fury.integration_tests.UserTypeSerializeSuite"
```