package org.reactome.server.utils.proxy;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;

import org.apache.http.*;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * ProxyServlet from http://edwardstx.net/wiki/attach/HttpProxyServlet/ProxyServlet.java
 * (This seems to be a derivative of Noodle -- http://noodle.tigris.org/)
 * <p/>
 * Patched to skip "Transfer-Encoding: chunked" headers, and avoid double slashes
 * in proxied URL's.
 * <p>
 * Modified for use in PRIDE projects by
 *
 * @author Antonio Fabregat (fabregat@ebi.ac.uk)
 * @author Florian Reisinger (florian@ebi.ac.uk)
 */
@SuppressWarnings({"UnusedDeclaration", "WeakerAccess"})
public class ProxyServlet extends HttpServlet {

    private static final boolean DEBUG = false;

    private static final int FOUR_KB = 4196;

    /**
     * Serialization UID.
     */
    private static final long serialVersionUID = 1L;
    /**
     * Key for redirect location header.
     */
    private static final String STRING_LOCATION_HEADER = "Location";
    /**
     * Key for content type header.
     */
    private static final String STRING_CONTENT_TYPE_HEADER_NAME = "Content-Type";
    /**
     * Key for content length header.
     */
    private static final String STRING_CONTENT_LENGTH_HEADER_NAME = "Content-Length";
    /**
     * Key for host header
     */
    private static final String STRING_HOST_HEADER_NAME = "Host";
    /**
     * The directory to use to temporarily store uploaded files
     */
    private static final File FILE_UPLOAD_TEMP_DIRECTORY = new File(System.getProperty("java.io.tmpdir"));

    // Proxy host params
    /**
     * The host to which we are proxying requests
     */
    private String stringProxyHost;
    /**
     * The port on the proxy host to which we are proxying requests. Default value is 80.
     */
    private int intProxyPort = 80;
    /**
     * The (optional) path on the proxy host to wihch we are proxying requests. Default value is "".
     */
    private String stringProxyPath = "";

    private String stringPrefixPath = "";

    /**
     * Setting that allows removing the initial path from client. Allows specifying /twitter/* as synonym for twitter.com.
     */
    private boolean removePrefix;
    /**
     * The maximum size for uploaded files in bytes. Default value is 5MB.
     */
    private int intMaxFileUploadSize = 5 * 1024 * 1024;
    private boolean isSecure;
    private boolean followRedirects;

    /**
     * Initialize the <code>ProxyServlet</code>
     *
     * @param servletConfig The Servlet configuration passed in by the servlet container
     */
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);

        // Get the proxy host
        String proxyProtocol = servletConfig.getInitParameter("proxyProtocol");
        isSecure = proxyProtocol == null || proxyProtocol.isEmpty() || proxyProtocol.equalsIgnoreCase("https");

        // Get the proxy host
        String stringProxyHostNew = servletConfig.getInitParameter("proxyHost");
        if (stringProxyHostNew == null || stringProxyHostNew.length() == 0) {
            throw new IllegalArgumentException("Proxy host not set, please set init-param 'proxyHost' in web.xml");
        }
        this.setProxyHost(stringProxyHostNew);
        // Get the proxy port if specified
        String stringProxyPortNew = servletConfig.getInitParameter("proxyPort");
        if (stringProxyPortNew != null && stringProxyPortNew.length() > 0) {
            this.setProxyPort(Integer.parseInt(stringProxyPortNew));
        }
        // Get the proxy path if specified
        String stringProxyPathNew = servletConfig.getInitParameter("proxyPath");
        if (stringProxyPathNew != null && stringProxyPathNew.length() > 0) {
            this.setProxyPath(stringProxyPathNew);
        }

        String stringPrefixPath = servletConfig.getInitParameter("prefixPath");
        if (stringPrefixPath != null && stringPrefixPath.length() > 0) {
            this.setPrefixPath(stringPrefixPath);
        }

        // Get the maximum file upload size if specified
        String stringMaxFileUploadSize = servletConfig.getInitParameter("maxFileUploadSize");
        if (stringMaxFileUploadSize != null && stringMaxFileUploadSize.length() > 0) {
            this.setMaxFileUploadSize(Integer.parseInt(stringMaxFileUploadSize));
        }
    }

    /**
     * Performs an HTTP GET request
     *
     * @param httpServletRequest  The {@link HttpServletRequest} object passed
     *                            in by the servlet engine representing the
     *                            client request to be proxied
     * @param httpServletResponse The {@link HttpServletResponse} object by which
     *                            we can send a proxied response to the client
     */
    public void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws IOException, ServletException {
        // Create a GET request
        String destinationUrl = this.getProxyURL(httpServletRequest);
        debug("GET Request URL: " + httpServletRequest.getRequestURL(),
                "Destination URL: " + destinationUrl);
        HttpGet getMethodProxyRequest = new HttpGet(destinationUrl);
        // Forward the request headers
        setProxyRequestHeaders(httpServletRequest, getMethodProxyRequest);

        // Execute the proxy request
        try {
            this.executeProxyRequest(getMethodProxyRequest, httpServletRequest, httpServletResponse);
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            debug(e.getMessage());
        }
    }

    /**
     * Performs an HTTP POST request
     *
     * @param httpServletRequest  The {@link HttpServletRequest} object passed
     *                            in by the servlet engine representing the
     *                            client request to be proxied
     * @param httpServletResponse The {@link HttpServletResponse} object by which
     *                            we can send a proxied response to the client
     */
    public void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws IOException, ServletException {
        // Create a standard POST request
        String contentType = httpServletRequest.getContentType();
        String destinationUrl = this.getProxyURL(httpServletRequest);
        debug("POST Request URL: " + httpServletRequest.getRequestURL(),
                "    Content Type: " + contentType,
                " Destination URL: " + destinationUrl);
        HttpPost postMethodProxyRequest = new HttpPost(destinationUrl);
        // Forward the request headers
        setProxyRequestHeaders(httpServletRequest, postMethodProxyRequest);
        // Check if this is a multipart (file upload) POST
        if (ServletFileUpload.isMultipartContent(httpServletRequest)) {
            this.handleMultipartPost(postMethodProxyRequest, httpServletRequest);
        } else {
            String encodedContentType = "application/x-www-form-urlencoded";
            if (contentType == null || encodedContentType.equals(contentType)) {
                this.handleStandardPost(postMethodProxyRequest, httpServletRequest);
            } else {
                this.handleContentPost(postMethodProxyRequest, httpServletRequest);
            }
        }


        // Execute the proxy request
        try {
            this.executeProxyRequest(postMethodProxyRequest, httpServletRequest, httpServletResponse);
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            debug(e.getMessage());
        }
    }

    /**
     * Sets up the given {@link HttpPost} to send the same multipart POST
     * data as was sent in the given {@link HttpServletRequest}
     *
     * @param postMethodProxyRequest The {@link HttpPost} that we are
     *                               configuring to send a multipart POST request
     * @param httpServletRequest     The {@link HttpServletRequest} that contains
     *                               the mutlipart POST data to be sent via the {@link HttpPost}
     */
    @SuppressWarnings("unchecked")
    private void handleMultipartPost(HttpPost postMethodProxyRequest, HttpServletRequest httpServletRequest)
            throws ServletException {
        // Create a factory for disk-based file items
        DiskFileItemFactory diskFileItemFactory = new DiskFileItemFactory();
        // Set factory constraints
        diskFileItemFactory.setSizeThreshold(this.getMaxFileUploadSize());
        diskFileItemFactory.setRepository(FILE_UPLOAD_TEMP_DIRECTORY);
        // Create a new file upload handler
        ServletFileUpload servletFileUpload = new ServletFileUpload(diskFileItemFactory);

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        // Parse the request
        try {
            // Get the multipart items as a list
            List<FileItem> listFileItems = servletFileUpload.parseRequest(httpServletRequest);
            // Iterate the multipart items list
            for (FileItem fileItemCurrent : listFileItems) {
                // If the current item is a form field, then create a string part
                if (fileItemCurrent.isFormField()) {
                    builder.addTextBody(fileItemCurrent.getFieldName(), fileItemCurrent.getString());
                } else {
                    // The item is a file upload, so we create a FilePart
                    builder.addBinaryBody(fileItemCurrent.getFieldName(), fileItemCurrent.get(), ContentType.DEFAULT_BINARY, fileItemCurrent.getName());
                }
            }

            HttpEntity entity = builder.build();
            postMethodProxyRequest.setEntity(entity);

            // The current content-type header (received from the client) IS of
            // type "multipart/form-data", but the content-type header also
            // contains the chunk boundary string of the chunks. Currently, this
            // header is using the boundary of the client request, since we
            // blindly copied all headers from the client request to the proxy
            // request. However, we are creating a new request with a new chunk
            // boundary string, so it is necessary that we re-set the
            // content-type string to reflect the new chunk boundary string

            postMethodProxyRequest.setHeader(STRING_CONTENT_TYPE_HEADER_NAME, entity.getContentType().getValue());

        } catch (FileUploadException fileUploadException) {
            throw new ServletException(fileUploadException);
        }
    }

    /**
     * Sets up the given {@link HttpPost} to send the same standard POST
     * data as was sent in the given {@link HttpServletRequest}
     *
     * @param postMethodProxyRequest The {@link HttpPost} that we are
     *                               configuring to send a standard POST request
     * @param httpServletRequest     The {@link HttpServletRequest} that contains
     *                               the POST data to be sent via the {@link HttpPost}
     */
    @SuppressWarnings("unchecked")
    private void handleStandardPost(HttpPost postMethodProxyRequest, HttpServletRequest httpServletRequest) {
        // Get the client POST data as a Map
        Map<String, String[]> mapPostParameters = httpServletRequest.getParameterMap();
        // Create a List to hold the NameValuePairs to be passed to the PostMethod
        List<NameValuePair> listNameValuePairs = new ArrayList<>();
        // Iterate the parameter names
        for (String stringParameterName : mapPostParameters.keySet()) {
            // Iterate the values for each parameter name
            String[] stringArrayParameterValues = mapPostParameters.get(stringParameterName);
            for (String stringParamterValue : stringArrayParameterValues) {
                // Create a NameValuePair and store in list
                BasicNameValuePair nameValuePair = new BasicNameValuePair(stringParameterName, stringParamterValue);
                listNameValuePairs.add(nameValuePair);
            }
        }
        // Set the proxy request POST data
        //noinspection ToArrayCallWithZeroLengthArrayArgument
        postMethodProxyRequest.setEntity(new UrlEncodedFormEntity(listNameValuePairs, Consts.UTF_8));
    }

    /**
     * Sets up the given {@link HttpPost} to send the same content POST
     * data (JSON, XML, etc.) as was sent in the given {@link HttpServletRequest}
     *
     * @param postMethodProxyRequest The {@link HttpPost} that we are
     *                               configuring to send a standard POST request
     * @param httpServletRequest     The {@link HttpServletRequest} that contains
     *                               the POST data to be sent via the {@link HttpPost}
     */
    private void handleContentPost(HttpPost postMethodProxyRequest,
                                   HttpServletRequest httpServletRequest)
            throws IOException {
        String contentType = httpServletRequest.getContentType();
        String postContent = IOUtils.toString(httpServletRequest.getReader());
        // Hack
        if (contentType.startsWith("text/x-gwt-rpc")) {
            String clientHost = httpServletRequest.getLocalName();
            if (clientHost.equals("127.0.0.1")) {
                clientHost = "localhost";
            }
            int clientPort = httpServletRequest.getLocalPort();
            String clientUrl = clientHost + ((clientPort != 80) ? ":" +
                    clientPort : "");
            String serverUrl = stringProxyHost + ((intProxyPort != 80) ? ":" +
                    intProxyPort : "") + stringPrefixPath;
            postContent = postContent.replace(clientUrl, serverUrl);
        }

        String encoding = httpServletRequest.getCharacterEncoding();
        debug("POST Content Type: " + contentType + " Encoding: " + encoding, "Content: " + postContent);

        StringEntity entity = new StringEntity(postContent, ContentType.APPLICATION_JSON);

        // Set the proxy request POST data
        postMethodProxyRequest.setEntity(entity);
    }

    /**
     * Executes the {@link HttpUriRequest} passed in and sends the proxy response
     * back to the client via the given {@link HttpServletResponse}
     *
     * @param httpMethodProxyRequest An object representing the proxy request to be made
     * @param httpServletResponse    An object by which we can send the proxied
     *                               response back to the client
     * @throws java.io.IOException Can be thrown by the {@link HttpClient}.executeMethod
     * @throws ServletException    Can be thrown to indicate that another error has occurred
     */
    private void executeProxyRequest(
            HttpUriRequest httpMethodProxyRequest,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse)
            throws IOException, ServletException, KeyManagementException, NoSuchAlgorithmException {

        if (isSecure) {
            // configure the SSLContext with a TrustManager
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[0], new TrustManager[]{new DefaultTrustManager()}, new SecureRandom());
            SSLContext.setDefault(ctx);
        }

        // Create a default HttpClient
        HttpClient httpClient = HttpClientBuilder.create().disableRedirectHandling().build();
        // Create a response
        HttpResponse httpResponse = httpClient.execute(httpMethodProxyRequest);

        // Execute the request
        int intProxyResponseCode = httpResponse.getStatusLine().getStatusCode();

        // Check if the proxy response is a redirect
        // The following code is adapted from org.tigris.noodle.filters.CheckForRedirect
        // Hooray for open source software
        if (intProxyResponseCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */ && intProxyResponseCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */) {
            String stringStatusCode = Integer.toString(intProxyResponseCode);
            String stringLocation = Arrays.toString(httpResponse.getHeaders(STRING_LOCATION_HEADER));
            if (stringLocation.isEmpty()) {
                throw new ServletException("Received status code: " + stringStatusCode + " but no " + STRING_LOCATION_HEADER + " header was found in the response");
            }
            // Modify the redirect to go to this proxy servlet rather that the proxied host
            String stringMyHostName = httpServletRequest.getServerName();
            if (httpServletRequest.getServerPort() != 80) {
                stringMyHostName += ":" + httpServletRequest.getServerPort();
            }
            stringMyHostName += httpServletRequest.getContextPath();
            if (followRedirects) {
                httpServletResponse.sendRedirect(stringLocation.replace(getProxyHostAndPort() + this.getProxyPath(), stringMyHostName));
                return;
            }
        } else if (intProxyResponseCode == HttpServletResponse.SC_NOT_MODIFIED) {
            // 304 needs special handling.  See:
            // http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304
            // We get a 304 whenever passed an 'If-Modified-Since'
            // header and the data on disk has not changed; server
            // responds w/ a 304 saying I'm not going to send the
            // body because the file has not changed.
            httpServletResponse.setIntHeader(STRING_CONTENT_LENGTH_HEADER_NAME, 0);
            httpServletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // Pass the response code back to the client
        httpServletResponse.setStatus(intProxyResponseCode);

        // Pass response headers back to the client
        Header[] headerArrayResponse = httpResponse.getAllHeaders();
        for (Header header : headerArrayResponse) {
            if ((!header.getName().equals("Transfer-Encoding") || !header.getValue().equals("chunked")) &&
                    (!header.getName().equals("Content-Encoding") || !header.getValue().equals("gzip"))) {
                httpServletResponse.setHeader(header.getName(), header.getValue());
            }
        }

        List<Header> responseHeaders = Arrays.asList(headerArrayResponse);
        if (isBodyParameterGzipped(responseHeaders)) {
            String response;
            byte[] aux;
            debug("GZipped: true");
            if (!followRedirects && intProxyResponseCode == HttpServletResponse.SC_MOVED_TEMPORARILY) {
                response = Arrays.toString(httpResponse.getHeaders(STRING_LOCATION_HEADER));
                aux = Arrays.toString(httpResponse.getHeaders(STRING_LOCATION_HEADER)).getBytes(UTF_8);
                httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                intProxyResponseCode = HttpServletResponse.SC_OK;
                httpServletResponse.setHeader(STRING_LOCATION_HEADER, response);
            } else {
                aux = IOUtils.toByteArray(httpResponse.getEntity().getContent());
            }

            httpServletResponse.setContentLength((int) httpResponse.getEntity().getContentLength());

            // Send the content to the client
            debug("Received status code: " + intProxyResponseCode, "Response is gzipped content");
            httpServletResponse.getOutputStream().write(aux);
        } else {
            byte[] aux = IOUtils.toByteArray(httpResponse.getEntity().getContent());
            httpServletResponse.getOutputStream().write(aux);
        }
    }

    /**
     * The response body will be assumed to be gzipped if the GZIP header has been set.
     *
     * @param responseHeaders of response headers
     * @return true if the body is gzipped
     */
    private boolean isBodyParameterGzipped(List<Header> responseHeaders) {
        for (Header header : responseHeaders) {
            if (header.getValue().equals("gzip") || header.getValue().contains("gzip")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A highly performant ungzip implementation. Do not refactor this without taking new timings.
     * See ElementTest in ehcache for timings
     *
     * @param gzipped the gzipped content
     * @return an ungzipped byte[]
     * @throws java.io.IOException when something bad happens
     */
    private byte[] ungzip(final InputStream gzipped) throws IOException {
        final GZIPInputStream inputStream = new GZIPInputStream(gzipped);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[FOUR_KB];
        int bytesRead = 0;
        while (bytesRead != -1) {
            bytesRead = inputStream.read(buffer, 0, FOUR_KB);
            if (bytesRead != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
        }
        byte[] ungzipped = byteArrayOutputStream.toByteArray();
        inputStream.close();
        byteArrayOutputStream.close();
        return ungzipped;
    }

    public String getServletInfo() {
        return "Jason's Proxy Servlet";
    }

    /**
     * Retreives all of the headers from the servlet request and sets them on
     * the proxy request
     *
     * @param httpServletRequest     The request object representing the client's
     *                               request to the servlet engine
     * @param httpMethodProxyRequest The request that we are about to send to
     *                               the proxy host
     */
    @SuppressWarnings("unchecked")
    private void setProxyRequestHeaders(HttpServletRequest httpServletRequest, HttpUriRequest httpMethodProxyRequest) {
        // Get an Enumeration of all of the header names sent by the client
        Enumeration enumerationOfHeaderNames = httpServletRequest.getHeaderNames();
        while (enumerationOfHeaderNames.hasMoreElements()) {
            String stringHeaderName = (String) enumerationOfHeaderNames.nextElement();
            if (stringHeaderName.equalsIgnoreCase(STRING_CONTENT_LENGTH_HEADER_NAME)) {
                continue;
            }
            // As per the Java Servlet API 2.5 documentation:
            //          Some headers, such as Accept-Language can be sent by clients
            //          as several headers each with a different value rather than
            //          sending the header as a comma separated list.
            // Thus, we get an Enumeration of the header values sent by the client
            Enumeration enumerationOfHeaderValues = httpServletRequest.getHeaders(stringHeaderName);
            while (enumerationOfHeaderValues.hasMoreElements()) {
                String stringHeaderValue = (String) enumerationOfHeaderValues.nextElement();
                // In case the proxy host is running multiple virtual servers,
                // rewrite the Host header to ensure that we get content from
                // the correct virtual server
                if (stringHeaderName.equalsIgnoreCase(STRING_HOST_HEADER_NAME)) {
                    stringHeaderValue = getProxyHostAndPort();
                }
                Header header = new BasicHeader(stringHeaderName, stringHeaderValue);
                // Set the same header on the proxy request
                httpMethodProxyRequest.setHeader(header);
            }
        }
    }

    // Accessors
    private String getProxyURL(HttpServletRequest httpServletRequest) {
        // Set the protocol to HTTP
        String protocol = (isSecure) ? "https://" : "http://";
        String stringProxyURL = protocol + this.getProxyHostAndPort();

        String path = this.getProxyPath();
        if (path == null || path.isEmpty()) {
            // simply use whatever servlet path that was part of the request as opposed to getting a preset/configurable proxy path
            path = httpServletRequest.getServletPath();
        }
        if (!removePrefix) {
            stringProxyURL += stringPrefixPath + path;
        }
        stringProxyURL += "/";

        // Handle the path given to the servlet
        String pathInfo = httpServletRequest.getPathInfo();
        if (pathInfo != null) {
            if (pathInfo.startsWith("/")) {
                // remove the first '/' from the pathInfo
                // to avoid double '/' in the URL
                stringProxyURL += pathInfo.substring(1);
            } else {
                // we can use the path as is
                stringProxyURL += pathInfo;
            }
        } else { // pathInfo == null
            // remove the trailing '/' we added before
            stringProxyURL = stringProxyURL.substring(0, stringProxyURL.length() - 1);
        }

        // Handle the query string
        if (httpServletRequest.getQueryString() != null) {
            stringProxyURL += "?" + httpServletRequest.getQueryString();
        }

        return stringProxyURL;
    }

    private String getProxyHostAndPort() {
        if (this.getProxyPort() == 80) {
            return this.getProxyHost();
        } else {
            return this.getProxyHost() + ":" + this.getProxyPort();
        }
    }


    protected String getProxyHost() {
        return this.stringProxyHost;
    }

    protected void setProxyHost(String stringProxyHostNew) {
        this.stringProxyHost = stringProxyHostNew;
    }

    protected int getProxyPort() {
        return this.intProxyPort;
    }

    protected void setSecure(boolean secure) {
        this.isSecure = secure;
    }

    protected void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    protected void setProxyPort(int intProxyPortNew) {
        this.intProxyPort = intProxyPortNew;
    }

    protected String getProxyPath() {
        return this.stringProxyPath;
    }

    protected void setProxyPath(String stringProxyPathNew) {
        this.stringProxyPath = stringProxyPathNew;
    }

    protected void setPrefixPath(String stringPrefixPath) {
        this.stringPrefixPath = stringPrefixPath;
    }

    protected void setRemovePrefix(boolean removePrefix) {
        this.removePrefix = removePrefix;
    }

    protected int getMaxFileUploadSize() {
        return this.intMaxFileUploadSize;
    }

    protected void setMaxFileUploadSize(int intMaxFileUploadSizeNew) {
        this.intMaxFileUploadSize = intMaxFileUploadSizeNew;
    }

    private void debug(String... msg) {
        //noinspection PointlessBooleanExpression,ConstantConditions
        if (!DEBUG) return;
        for (String m : msg) {
            System.out.println("[DEBUG] " + m);
        }
    }

    //DefaultTrustManager accepts all certificates
    private static class DefaultTrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] arg0, String arg1) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] arg0, String arg1) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }
}
