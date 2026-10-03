package com.verso.support;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A multipart/form-data body with one file part, for java.net.http (which has no multipart builder). */
public record Multipart(String contentType, HttpRequest.BodyPublisher body) {

    public static Multipart file(String partName, String fileName, String partContentType, byte[] content) {
        String boundary = "verso-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + partName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + partContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart("multipart/form-data; boundary=" + boundary, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
    }

    public static Multipart pdf(String fileName, byte[] content) {
        return file("file", fileName, "application/pdf", content);
    }
}
