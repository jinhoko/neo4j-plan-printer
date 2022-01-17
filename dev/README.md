# Build

From container `maven:3.8.4-jdk-11`, 

```
cd neo4j

export MAVEN_OPTS="-Xmx2g" && mvn install -DskipTests -Doverwrite -Dlicense.skip=true -DminimalBuild  --fail-at-end --threads 8
 ```

 The output tar.gz file is locaed in `packaging/standalone/target/neo4j-community-4.3.9-SNAPSHOT-unix.tar.gz`