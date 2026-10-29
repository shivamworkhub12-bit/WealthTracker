package com.wealthos.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.stereotype.Component;

@Component
public class GmailEmailReader {
    public record Text(String value,boolean truncated) {}
    private static final int LIMIT=6000;
    public String header(JsonNode message,String name) {
        for(JsonNode h:message.path("payload").path("headers")) if(name.equalsIgnoreCase(h.path("name").asText())) return h.path("value").asText();
        return "";
    }
    public String sender(JsonNode message) {
        String from=header(message,"From").trim();
        Matcher m=Pattern.compile("(?:^|<)([A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+)(?:>|$)").matcher(from);
        return m.find()?m.group(1).toLowerCase(Locale.ROOT):"";
    }
    public boolean allowed(String sender,Set<String> domains) {
        int at=sender.lastIndexOf('@');if(at<1) return false;
        String domain=sender.substring(at+1);
        return domains.stream().anyMatch(d->domain.equals(d) || domain.endsWith("."+d));
    }
    public boolean authenticated(JsonNode message,String sender) {
        // The receiving Gmail server's Authentication-Results header is the authority,
        // not an arbitrary DKIM/SPF claim in the email body.
        String value=header(message,"Authentication-Results").toLowerCase(Locale.ROOT);
        if(!value.startsWith("mx.google.com;") || !value.matches("(?s).*\\bdmarc=pass\\b.*")) return false;
        Matcher aligned=Pattern.compile("\\bheader\\.from=([a-z0-9.-]+)").matcher(value);
        if(!aligned.find()) return false;
        String domain=sender.substring(sender.lastIndexOf('@')+1),authenticated=aligned.group(1);
        return domain.equals(authenticated) || domain.endsWith("."+authenticated);
    }
    public Text text(JsonNode message) {
        List<String> plain=new ArrayList<>(),html=new ArrayList<>();collect(message.path("payload"),plain,html,0);
        String value=String.join("\n",plain.isEmpty()?html:plain);
        if(value.length()>LIMIT) return new Text(value.substring(0,LIMIT),true);
        return new Text(value,false);
    }
    private void collect(JsonNode part,List<String> plain,List<String> html,int depth) {
        if(depth>10 || !part.path("filename").asText().isBlank()) return;
        for(JsonNode h:part.path("headers")) if(h.path("name").asText().equalsIgnoreCase("Content-Disposition") && h.path("value").asText().toLowerCase(Locale.ROOT).startsWith("attachment")) return;
        String mime=part.path("mimeType").asText(),encoded=part.path("body").path("data").asText();
        if(!encoded.isBlank() && encoded.length()<700000 && (mime.equals("text/plain") || mime.equals("text/html"))) {
            try {
                String decoded=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);
                if(mime.equals("text/plain")) plain.add(decoded);else html.add(stripHtml(decoded));
            } catch(IllegalArgumentException ignored) { }
        }
        for(JsonNode child:part.path("parts")) collect(child,plain,html,depth+1);
    }
    private String stripHtml(String html) {
        StringBuilder text=new StringBuilder();
        try {
            new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback() {
                int hidden;
                @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet a,int pos) { if(tag==HTML.Tag.SCRIPT || tag==HTML.Tag.STYLE) hidden++;if(tag.isBlock() && hidden==0) text.append('\n'); }
                @Override public void handleEndTag(HTML.Tag tag,int pos) { if(tag==HTML.Tag.SCRIPT || tag==HTML.Tag.STYLE) hidden=Math.max(0,hidden-1);if((tag.isBlock() || tag==HTML.Tag.TD) && hidden==0) text.append('\n'); }
                @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet a,int pos) { if(tag==HTML.Tag.BR && hidden==0) text.append('\n'); }
                @Override public void handleText(char[] data,int pos) { if(hidden==0) text.append(data).append(' '); }
            },true);
        } catch(IOException ignored) { }
        return text.toString().replaceAll("[ \\t]+"," ").replaceAll(" *\\n *","\n").trim();
    }
}
