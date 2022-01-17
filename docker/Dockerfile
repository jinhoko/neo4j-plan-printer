# syntax=docker/dockerfile:1
FROM maven:3.8.4-jdk-11
COPY ./neo4j-community-4.3.9-SNAPSHOT-unix.tar.gz /root/
WORKDIR /root/
RUN tar -zxf /root/neo4j-community-4.3.9-SNAPSHOT-unix.tar.gz && \
	mv /root/neo4j-community-4.3.9-SNAPSHOT/ /root/neo4j/
WORKDIR /root/neo4j/
ENV PATH=/root/neo4j/bin/:$PATH

# set id/pw for cypher-shell and disable query cache
RUN bin/neo4j-admin set-default-admin neo4j && \
	bin/neo4j-admin set-initial-password neo4j && \
	echo "dbms.query_cache_size=0" >> conf/neo4j.conf && \
	echo "cypher.min_replan_interval=1s" >> conf/neo4j.conf && \
	echo "cypher.statistics_divergence_threshold=0.0" >> conf/neo4j.conf 
CMD bin/neo4j console