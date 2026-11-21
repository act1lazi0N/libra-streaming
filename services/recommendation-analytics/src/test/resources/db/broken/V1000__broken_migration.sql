-- Test only: fails after its first statement, to prove a failed migration leaves nothing behind.
CREATE TABLE half_applied (id bigint PRIMARY KEY);
INSERT INTO input_checkpoints (topic) VALUES (NULL);
