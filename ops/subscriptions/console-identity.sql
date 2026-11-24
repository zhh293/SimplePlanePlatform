CREATE OR REPLACE SQL SECURITY DEFINER VIEW sso_authsystem_v2.sp_subscription_directory AS
SELECT subject_id, username_normalized AS username, display_name, enabled
FROM sso_authsystem_v2.sso_user;
GRANT SELECT ON sso_authsystem_v2.sp_subscription_directory TO 'simpleplane_subscriptions'@'%';

DELIMITER //
DROP PROCEDURE IF EXISTS sso_authsystem_v2.sp_subscription_create//
CREATE PROCEDURE sso_authsystem_v2.sp_subscription_create(
    IN subject_ VARCHAR(128), IN username_ VARCHAR(128), IN display_ VARCHAR(100),
    IN encoded_ VARCHAR(255), IN actor_ VARCHAR(128))
SQL SECURITY DEFINER
BEGIN
    INSERT INTO sso_authsystem_v2.sso_user(subject_id,username_normalized,display_name,password_hash,enabled,email_verified)
        VALUES(subject_,username_,display_,encoded_,TRUE,FALSE);
    INSERT INTO sso_authsystem_v2.sso_audit_event(event_type,outcome,correlation_id,occurred_at,actor_id,resource_type,resource_id)
        VALUES('ADMIN_PROVISIONED','SUCCESS',UUID(),CURRENT_TIMESTAMP(6),actor_,'USER',subject_);
END//
DROP PROCEDURE IF EXISTS sso_authsystem_v2.sp_subscription_password//
CREATE PROCEDURE sso_authsystem_v2.sp_subscription_password(
    IN subject_ VARCHAR(128), IN encoded_ VARCHAR(255), IN actor_ VARCHAR(128))
SQL SECURITY DEFINER
BEGIN
    IF NOT EXISTS (SELECT 1 FROM simpleplane_subscriptions.sp_console_user WHERE subject_id=subject_) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Not a console-created account';
    END IF;
    UPDATE sso_authsystem_v2.sso_user SET password_hash=encoded_ WHERE subject_id=subject_;
    INSERT INTO sso_authsystem_v2.sso_audit_event(event_type,outcome,correlation_id,occurred_at,actor_id,resource_type,resource_id)
        VALUES('ADMIN_PASSWORD_RESET','SUCCESS',UUID(),CURRENT_TIMESTAMP(6),actor_,'USER',subject_);
END//
DELIMITER ;
GRANT EXECUTE ON PROCEDURE sso_authsystem_v2.sp_subscription_create TO 'simpleplane_subscriptions'@'%';
GRANT EXECUTE ON PROCEDURE sso_authsystem_v2.sp_subscription_password TO 'simpleplane_subscriptions'@'%';
