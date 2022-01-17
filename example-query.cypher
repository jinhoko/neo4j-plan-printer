CREATE (n:Person {name: 'Andy', title: 'Developer'});
CREATE (n:Person {name: 'Bndy', title: 'Developer'});

MATCH
  (a:Person),
  (b:Person)
WHERE a.name = 'Andy' AND b.name = 'Bndy'
CREATE (a)-[r:LIKES]->(b);

MATCH (n)-[r:LIKES]-(m) RETURN n,m;