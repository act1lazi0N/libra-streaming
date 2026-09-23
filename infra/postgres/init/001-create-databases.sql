CREATE USER libra_core WITH PASSWORD 'core_local';
CREATE USER libra_media WITH PASSWORD 'media_local';
CREATE USER libra_recommendation WITH PASSWORD 'recommendation_local';

CREATE DATABASE libra_core OWNER libra_core;
CREATE DATABASE libra_media OWNER libra_media;
CREATE DATABASE libra_recommendation OWNER libra_recommendation;

