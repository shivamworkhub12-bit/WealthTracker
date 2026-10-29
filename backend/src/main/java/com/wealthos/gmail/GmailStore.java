package com.wealthos.gmail;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GmailStore {
    private final JdbcTemplate jdbc;
    public GmailStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Connection(UUID generation,String email,String encryptedToken,Instant collectAfter,Instant lastSynced,
                             Instant windowAfter,Instant windowBefore,String pageToken,String state,String message,Instant retryAfter,int failures) {}
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant(); }
    public Optional<Connection> connection() {
        return jdbc.query("SELECT * FROM gmail_connection WHERE id=1",(rs,n)->new Connection(rs.getObject("generation",UUID.class),rs.getString("email"),rs.getString("encrypted_refresh_token"),instant(rs,"collect_after"),instant(rs,"last_synced_at"),instant(rs,"window_after"),instant(rs,"window_before"),rs.getString("page_token"),rs.getString("state"),rs.getString("message"),instant(rs,"retry_after"),rs.getInt("failures"))).stream().findFirst();
    }
    public void connect(String email,String encrypted,String scope,int lookbackDays) {
        Instant now=Instant.now();
        jdbc.update("INSERT INTO gmail_connection(id,generation,email,encrypted_refresh_token,granted_scope,connected_at,collect_after,state,message) VALUES(1,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET generation=excluded.generation,email=excluded.email,encrypted_refresh_token=excluded.encrypted_refresh_token,granted_scope=excluded.granted_scope,connected_at=excluded.connected_at,collect_after=excluded.collect_after,last_synced_at=NULL,window_after=NULL,window_before=NULL,page_token=NULL,state=excluded.state,message=excluded.message,retry_after=NULL,failures=0",
                UUID.randomUUID(),email,encrypted,scope,Timestamp.from(now),Timestamp.from(now.minusSeconds(lookbackDays*86400L)),"CONNECTED","Connected. Bank alerts will sync automatically while WealthOS is running.");
    }
    public boolean same(UUID generation) { return connection().map(c->c.generation().equals(generation)).orElse(false); }
    public void rotate(UUID generation,String token) { jdbc.update("UPDATE gmail_connection SET encrypted_refresh_token=? WHERE id=1 AND generation=?",token,generation); }
    public void disconnect() { jdbc.update("DELETE FROM gmail_connection WHERE id=1"); }
    public boolean seen(String mailbox,String id) { return jdbc.queryForObject("SELECT count(*) FROM gmail_seen_messages WHERE mailbox=? AND message_id=?",Long.class,mailbox,id)>0; }
    public void markSeen(String mailbox,String id) { jdbc.update("INSERT INTO gmail_seen_messages(mailbox,message_id) VALUES(?,?) ON CONFLICT DO NOTHING",mailbox,id); }
    public long checked(String mailbox) { return jdbc.queryForObject("SELECT count(*) FROM gmail_seen_messages WHERE mailbox=?",Long.class,mailbox); }
    public void startWindow(UUID generation,Instant after,Instant before) {
        jdbc.update("UPDATE gmail_connection SET window_after=?,window_before=?,page_token=NULL,state='SYNCING',message='Checking bank transaction alerts.' WHERE id=1 AND generation=?",Timestamp.from(after),Timestamp.from(before),generation);
    }
    public void pageDone(UUID generation,String next,Instant before) {
        if(next==null || next.isBlank()) jdbc.update("UPDATE gmail_connection SET window_after=NULL,window_before=NULL,page_token=NULL,last_synced_at=?,state='CONNECTED',message='Bank transaction alerts are up to date.',retry_after=NULL,failures=0 WHERE id=1 AND generation=?",Timestamp.from(before),generation);
        else jdbc.update("UPDATE gmail_connection SET page_token=?,state='CATCHING_UP',message='Catching up on bank alerts; the next page is queued.',retry_after=NULL,failures=0 WHERE id=1 AND generation=?",next,generation);
    }
    public void failed(Connection c,String state,String message) {
        long wait=Math.min(3600,60L << Math.min(6,c.failures()));
        jdbc.update("UPDATE gmail_connection SET state=?,message=?,failures=failures+1,retry_after=? WHERE id=1 AND generation=?",state,message,Timestamp.from(Instant.now().plusSeconds(wait)),c.generation());
    }
}
