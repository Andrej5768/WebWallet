-- Which client the session belongs to: null for the web client (cookie), ANDROID / IOS for the apps (body)
alter table refresh_tokens add column client varchar(16);
