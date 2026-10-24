package com.verso.connector.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.commons.socket.ProxySocketFactory;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;
import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.SourcePaths;
import com.verso.connector.domain.SourceSettings;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * SMB2/3 through smbj (ADR-0038; Apache-2.0, docs/versions.md). Hardening, all on by construction:
 * <ul>
 *   <li>egress: the host is resolved and checked by {@link EgressGuard}; the client connects to that IP literal;</li>
 *   <li>DFS off: a referral cannot send the session to another server;</li>
 *   <li>signing required by default (verso.connector.smb.signing-required), SMB3 encryption optional;</li>
 *   <li>timeouts on connect, socket reads and every request;</li>
 *   <li>read-only: files are opened for read data and attributes only, sharing read, write and delete so users are
 *       never locked out;</li>
 *   <li>no links: entries with the reparse-point attribute (symbolic links, junctions, mount points) are never
 *       followed; files are opened with FILE_OPEN_REPARSE_POINT and re-checked, so a file swapped for a link between
 *       listing and reading is refused; offline (HSM) files are not recalled;</li>
 *   <li>every server-supplied name is checked by {@link SourcePaths} before it becomes part of a path.</li>
 * </ul>
 * Nothing from the server (messages, names) reaches the log; failures become fixed codes ({@link SourceException}).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SmbFileSourceFactory implements FileSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(SmbFileSourceFactory.class);
    private static final long ATTR_DIRECTORY = 0x10L;
    private static final long ATTR_REPARSE_POINT = 0x400L;
    private static final long ATTR_OFFLINE = 0x1000L;
    private static final long ATTR_RECALL_ON_OPEN = 0x40000L;
    private static final long ATTR_RECALL_ON_DATA_ACCESS = 0x400000L;
    private static final long NOT_READ = ATTR_REPARSE_POINT | ATTR_OFFLINE | ATTR_RECALL_ON_OPEN | ATTR_RECALL_ON_DATA_ACCESS;

    private final EgressGuard egress;
    private final ConnectorCredentials credentials;
    private final ConnectorProperties properties;

    public SmbFileSourceFactory(EgressGuard egress, ConnectorCredentials credentials, ConnectorProperties properties) {
        this.egress = egress;
        this.credentials = credentials;
        this.properties = properties;
    }

    @Override
    public FileSource open(SourceSettings settings) {
        InetAddress address = egress.resolve(settings.host());
        ConnectorProperties.Smb smb = properties.smb();
        SmbConfig config = SmbConfig.builder()
                .withDfsEnabled(false)
                .withSigningRequired(smb.signingRequired())
                .withEncryptData(smb.encryptData())
                .withTimeout(smb.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .withSoTimeout(smb.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .withSocketFactory(new ProxySocketFactory(Proxy.NO_PROXY, (int) smb.connectTimeout().toMillis()))
                .build();
        SMBClient client = new SMBClient(config);
        char[] password = credentials.password(settings.credentialRef());
        Connection connection = null;
        try {
            try {
                connection = client.connect(address.getHostAddress(), settings.port());
            } catch (IOException | RuntimeException e) {
                throw new SourceException("SOURCE_UNREACHABLE", true, e);
            }
            Session session;
            try {
                String domain = settings.domain() == null ? "" : settings.domain();
                session = connection.authenticate(new AuthenticationContext(settings.username(), password, domain));
            } catch (RuntimeException e) {
                throw new SourceException("SOURCE_AUTH_FAILED", true, e);
            }
            Share share;
            try {
                share = session.connectShare(settings.share());
            } catch (SMBApiException e) {
                throw new SourceException(e.getStatus() == NtStatus.STATUS_BAD_NETWORK_NAME ? "SHARE_NOT_FOUND"
                        : e.getStatus() == NtStatus.STATUS_ACCESS_DENIED ? "SOURCE_AUTH_FAILED" : "SOURCE_ERROR", true, e);
            } catch (RuntimeException e) {
                throw new SourceException("SOURCE_ERROR", true, e);
            }
            if (!(share instanceof DiskShare disk)) {
                closeQuietly(share);
                throw SourceException.fatal("SHARE_NOT_FOUND");
            }
            return new SmbFileSource(client, connection, disk, settings, properties);
        } catch (SourceException e) {
            closeQuietly(connection);
            client.close();
            throw e;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("SMB close failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    /** One open share; not thread-safe (one sync worker uses it). */
    static final class SmbFileSource implements FileSource {

        private final SMBClient client;
        private final Connection connection;
        private final DiskShare share;
        private final SourceSettings settings;
        private final ConnectorProperties properties;

        SmbFileSource(SMBClient client, Connection connection, DiskShare share, SourceSettings settings,
                      ConnectorProperties properties) {
            this.client = client;
            this.connection = connection;
            this.share = share;
            this.settings = settings;
            this.properties = properties;
        }

        @Override
        public Listing list(Runnable keepAlive) {
            List<ListedFile> files = new ArrayList<>();
            Deque<String[]> folders = new ArrayDeque<>();
            folders.push(new String[]{"", "0"});
            boolean complete = true;
            String problem = null;
            int unsafe = 0;
            int entries = 0;
            while (!folders.isEmpty()) {
                keepAlive.run();
                String[] next = folders.pop();
                String folder = next[0];
                int depth = Integer.parseInt(next[1]);
                List<FileIdBothDirectoryInformation> children;
                try {
                    children = share.list(SourcePaths.toShare(settings.pathPrefix(), folder));
                } catch (SMBApiException e) {
                    if (folder.isEmpty()) {
                        // The prefix itself: nothing can be listed, so nothing may be judged deleted.
                        boolean missing = e.getStatus() == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
                                || e.getStatus() == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND;
                        throw new SourceException(missing ? "PATH_NOT_FOUND" : "PATH_UNREADABLE", true, e);
                    }
                    complete = false;
                    if (problem == null) problem = "DIRECTORY_UNREADABLE";
                    continue;
                } catch (RuntimeException e) {
                    throw new SourceException("SOURCE_UNREACHABLE", true, e);
                }
                for (FileIdBothDirectoryInformation child : children) {
                    String name = child.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    if (++entries > properties.maxListedEntries()) {
                        return new Listing(files, false, "LISTING_TOO_LARGE", unsafe);
                    }
                    long attributes = child.getFileAttributes();
                    if (!SourcePaths.isSafeName(name)) {
                        unsafe++;
                        continue;
                    }
                    String path;
                    try {
                        path = SourcePaths.child(folder, name);
                    } catch (IllegalArgumentException e) {
                        unsafe++;
                        continue;
                    }
                    if ((attributes & ATTR_DIRECTORY) != 0) {
                        if ((attributes & ATTR_REPARSE_POINT) != 0) {
                            // A junction or a directory symlink: never followed. What is behind it is not listed, so
                            // the listing is not complete (no file "behind" it may be judged deleted).
                            unsafe++;
                            complete = false;
                            if (problem == null) problem = "LINK_NOT_FOLLOWED";
                        } else if (depth + 1 > properties.maxDepth()) {
                            complete = false;
                            if (problem == null) problem = "LISTING_TOO_DEEP";
                        } else {
                            folders.push(new String[]{path, Integer.toString(depth + 1)});
                        }
                        continue;
                    }
                    if ((attributes & NOT_READ) != 0) {
                        unsafe++;
                        continue;
                    }
                    if (!settings.inScope(path)) continue;
                    files.add(new ListedFile(path, child.getFileId(), child.getEndOfFile(),
                            child.getLastWriteTime().toInstant().truncatedTo(ChronoUnit.MICROS)));
                }
            }
            return new Listing(files, complete, problem, unsafe);
        }

        @Override
        public byte[] read(String relativePath, long maxBytes) {
            String smbPath = SourcePaths.toShare(settings.pathPrefix(), relativePath);
            Set<SMB2CreateOptions> options = EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
                    SMB2CreateOptions.FILE_OPEN_REPARSE_POINT);
            try (File file = share.openFile(smbPath, EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                    null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, options)) {
                long attributes = file.getFileInformation().getBasicInformation().getFileAttributes();
                if ((attributes & NOT_READ) != 0) throw SourceException.file("UNSAFE_LINK");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                // A whole-file deadline on top of the per-request timeout: a server that trickles one chunk per minute
                // would otherwise hold the run past its lease (resilience review R1), and another instance would start.
                long deadline = System.nanoTime() + properties.smb().timeout().multipliedBy(2).toNanos();
                try (InputStream in = file.getInputStream()) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (System.nanoTime() - deadline > 0) throw SourceException.file("READ_TIMEOUT");
                        if (out.size() + (long) n > maxBytes) throw SourceException.file("TOO_LARGE");
                        out.write(buffer, 0, n);
                    }
                }
                return out.toByteArray();
            } catch (SourceException e) {
                throw e;
            } catch (SMBApiException e) {
                NtStatus status = e.getStatus();
                String code = status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
                        || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ? "FILE_VANISHED"
                        : status == NtStatus.STATUS_ACCESS_DENIED ? "ACCESS_DENIED"
                        : status == NtStatus.STATUS_STOPPED_ON_SYMLINK ? "UNSAFE_LINK" : "READ_FAILED";
                throw new SourceException(code, false, e);
            } catch (IOException | RuntimeException e) {
                // The transport failed: every further read would fail too, the run stops.
                throw new SourceException("SOURCE_UNREACHABLE", true, e);
            }
        }

        @Override
        public void close() {
            closeQuietly(share);
            closeQuietly(connection);
            client.close();
        }
    }
}
