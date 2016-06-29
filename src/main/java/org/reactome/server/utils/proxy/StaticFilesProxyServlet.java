package org.reactome.server.utils.proxy;

import org.apache.commons.io.FileUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

/**
 * ProxyServlet access files through http. Used to access static resources in development mode
 * without the need of installing an apache server.
 *
 * @author Antonio Fabregat <fabregat@ebi.ac.uk>
 */
public class StaticFilesProxyServlet extends HttpServlet{

    /**
     * The (optional) path on the proxy host to which we are proxying requests. Default value is "".
     */
    private String stringFilePath = "";

    /**
     * Initialize the <code>ProxyServlet</code>
     * @param servletConfig The Servlet configuration passed in by the servlet container
     */
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);

        // Get the proxy path if specified
        String stringProxyFilePathNew = servletConfig.getInitParameter("proxyFilePath");
        if (stringProxyFilePathNew != null && stringProxyFilePathNew.length() > 0) {
            this.setProxyFilePath(stringProxyFilePathNew);
        }
    }

    /**
     * Performs an HTTP GET request
     * @param httpServletRequest The {@link HttpServletRequest} object passed
     *                            in by the servlet engine representing the
     *                            client request to be proxied
     * @param httpServletResponse The {@link HttpServletResponse} object by which
     *                             we can send a proxied response to the client
     */
    public void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws IOException, ServletException {
        String pathInfo = httpServletRequest.getPathInfo();
        File file = new File(this.stringFilePath + pathInfo);
        byte[] rtn = FileUtils.readFileToString(file).getBytes();
        httpServletResponse.getOutputStream().write(rtn);
    }

    protected String getProxyFilePath() {
        return this.stringFilePath;
    }

    protected void setProxyFilePath(String stringProxyPathNew) {
        this.stringFilePath = stringProxyPathNew;
    }
}
