package com.wealthos.gmail;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

@Component
public class GmailTokenCipher {
    private final Path file;
    private final SecureRandom random=new SecureRandom();
    public GmailTokenCipher(GmailSettings settings) { file=settings.keyFile; }
    private synchronized byte[] key(boolean create) throws Exception {
        if(!Files.exists(file)) {
            if(!create) throw new IllegalStateException("Gmail token key is missing; reconnect Gmail");
            Files.createDirectories(file.toAbsolutePath().getParent());
            byte[] key=new byte[32];random.nextBytes(key);
            try { Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.write(file,key); }
            catch(FileAlreadyExistsException ignored) { }
        }
        byte[] key=Files.readAllBytes(file);
        if(key.length!=32) throw new IllegalStateException("Gmail token key is invalid; reconnect Gmail");
        return key;
    }
    public String encrypt(String text) {
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(true),"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=cipher.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] result=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(encrypted,0,result,iv.length,encrypted.length);
            return "v1:"+Base64.getEncoder().encodeToString(result);
        } catch(Exception ex) { throw new IllegalStateException("Gmail credentials could not be protected locally"); }
    }
    public String decrypt(String encoded) {
        try {
            if(!encoded.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] payload=Base64.getDecoder().decode(encoded.substring(3));
            if(payload.length<29) throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(false),"AES"),new GCMParameterSpec(128,Arrays.copyOf(payload,12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(payload,12,payload.length)),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception ex) { throw new IllegalStateException("Gmail credentials are unavailable; reconnect Gmail"); }
    }
}
