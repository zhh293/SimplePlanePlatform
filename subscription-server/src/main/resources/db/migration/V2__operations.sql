CREATE TABLE sp_group (
    group_id VARCHAR(96) PRIMARY KEY,
    label VARCHAR(100) NOT NULL
);
INSERT INTO sp_group (group_id,label) SELECT DISTINCT group_id,group_id FROM subscription_account;
INSERT INTO sp_group (group_id,label) SELECT 'standard','标准线路' WHERE NOT EXISTS
    (SELECT 1 FROM sp_group WHERE group_id='standard');
ALTER TABLE subscription_account ADD CONSTRAINT fk_subscription_group FOREIGN KEY (group_id) REFERENCES sp_group(group_id);
CREATE TABLE sp_node (
    node_id VARCHAR(96) PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port INTEGER NOT NULL,
    server_name VARCHAR(253) NOT NULL,
    enabled BOOLEAN NOT NULL,
    configured BOOLEAN NOT NULL,
    server_ca TEXT,
    sealed_material BLOB
);
CREATE TABLE sp_group_node (
    group_id VARCHAR(96) NOT NULL,
    node_id VARCHAR(96) NOT NULL,
    PRIMARY KEY (group_id,node_id),
    FOREIGN KEY (group_id) REFERENCES sp_group(group_id) ON DELETE CASCADE,
    FOREIGN KEY (node_id) REFERENCES sp_node(node_id) ON DELETE CASCADE
);
CREATE TABLE sp_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    happened_at BIGINT NOT NULL,
    actor VARCHAR(255) NOT NULL,
    action_name VARCHAR(96) NOT NULL,
    target VARCHAR(255) NOT NULL
);
CREATE TABLE sp_console_user (
    subject_id VARCHAR(128) PRIMARY KEY
);
CREATE TABLE sp_imported_node (
    node_id VARCHAR(96) PRIMARY KEY
);
