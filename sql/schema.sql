-- Tables de SHProgress avec SHNetwork. Le plugin les crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à les créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.shprogress_progress (
    uuid         CHAR(36)     NOT NULL,          -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    advancement  VARCHAR(128) NOT NULL,          -- identifiant du progrès : <arbre>/<progrès>
    progress     INT          NOT NULL DEFAULT 0, -- progression, augmentée par « progress = LEAST(progress + gain, quantité) »
    PRIMARY KEY (uuid, advancement)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS bo_net.shprogress_rewards (
    uuid         CHAR(36)     NOT NULL,
    advancement  VARCHAR(128) NOT NULL,
    claimed_at   BIGINT       NOT NULL,           -- heure de la récompense (ms), la ligne est créée par INSERT IGNORE
    PRIMARY KEY (uuid, advancement)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shprogress'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE ON bo_net.shprogress_progress TO 'shprogress'@'127.0.0.1';
GRANT SELECT, INSERT ON bo_net.shprogress_rewards TO 'shprogress'@'127.0.0.1';
