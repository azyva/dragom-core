/*
 * Copyright 2015 - 2017 AZYVA INC. INC.
 *
 * This file is part of Dragom.
 *
 * Dragom is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Dragom is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Dragom.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.azyva.dragom.jenkins.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

import javax.xml.bind.DatatypeConverter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.azyva.dragom.jenkins.JenkinsClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;


/**
 * Main implementation of {@link JenkinsClient}.
 *
 * @author David Raymond
 */
public class DefaultJenkinsClientImpl implements JenkinsClient {
  /**
   * Base URL of Jenkins.
   */
  private String baseUrl;

  /**
   * User to use to connect to Jenkins.
   */
  private String user;

  /**
   * Password.
   */
  private String password;

  /**
   * Base-64 encoding of user:password.
   */
  private String basicAuthBase64;

  /**
   * Main implementation of {@link org.azyva.dragom.jenkins.JenkinsClient.Build}.
   */
  public class DefaultBuildImpl implements JenkinsClient.Build {
    /**
     * Previous BuildState reported by {@link #getBuildState}.
     */
    BuildState buildStatePrevious;

    /**
     * Job URL.
     */
    String jobUrl;

    /**
     * Queue item URL. This is the initial link we have with the build. Even when it
     * is out of the queue, which URL is still usable.
     */
    String queueItemUrl;

    /**
     * Build URL. null until the build is out of the queue.
     */
    String buildUrl;

    /**
     * Job (full name).
     */
    String job;

    /**
     * Build number.
     */
    int buildNumber;

    /**
     * Build (display) name.
     */
    String buildName;

    /**
     * Next console start index. Used by {@link #getNextConsoleChunk} for progressive
     * console output. -1 if no more data is available and build is (supposed to be)
     * completed.
     */
    int nextConsoleStart;

    /**
     * Constructor.
     *
     * @param jobUrl Job URL.
     * @param job Job (full name).
     * @param queueItemUrl Queue item URL.
     */
    private DefaultBuildImpl(String jobUrl, String job, String queueItemUrl) {
      this.jobUrl = jobUrl;
      this.job = job;
      this.queueItemUrl = queueItemUrl;
      this.buildStatePrevious = BuildState.QUEUED;
    }

    @Override
    public BuildState getBuildState() {
      Document document;
      NodeList nodeList;

      if (this.buildStatePrevious == BuildState.QUEUED) {
        document = DefaultJenkinsClientImpl.this.getForXml(this.queueItemUrl + "api/xml");

        nodeList = document.getElementsByTagName("cancelled");

        if (nodeList.getLength() == 0) {
          return BuildState.QUEUED;
        } else {
          String cancelled;

          cancelled = nodeList.item(0).getTextContent();

          if (cancelled.equals("true")) {
            this.buildStatePrevious = BuildState.CANCELLED;
            return BuildState.CANCELLED;
          } else {
            nodeList = document.getElementsByTagName("executable");

            if (nodeList.getLength() != 0) {
              this.buildStatePrevious = BuildState.RUNNING;
              this.buildNumber = Integer.parseInt(((Element)(nodeList.item(0))).getElementsByTagName("number").item(0).getTextContent());
              this.buildUrl = ((Element)(nodeList.item(0))).getElementsByTagName("url").item(0).getTextContent();

              // We do not return the state since the build may already be completed. We must
              // query the build.
            } else {
              return BuildState.QUEUED;
            }

          }
        }
      }

      if (this.buildStatePrevious == BuildState.RUNNING) {
        document = DefaultJenkinsClientImpl.this.getForXml(this.buildUrl + "api/xml");

        this.buildName = document.getElementsByTagName("displayName").item(0).getTextContent();

        nodeList = document.getElementsByTagName("result");

        if (nodeList.getLength() == 0) {
          return BuildState.RUNNING;
        } else {
          String result;

          result = nodeList.item(0).getTextContent();

          if (result.equals("ABORTED")) {
            this.buildStatePrevious = BuildState.ABORTED;
          } else if (result.equals("FAILURE")) {
            this.buildStatePrevious = BuildState.FAILED;
          } else if (result.equals("UNSTABLE")) {
            this.buildStatePrevious = BuildState.UNSTABLE;
          } else if (result.equals("SUCCESS")) {
            this.buildStatePrevious = BuildState.SUCCESS;
          }

          return this.buildStatePrevious;
        }
      }

      return this.buildStatePrevious;
    }

    @Override
    public String getJobUrl() {
      return this.jobUrl;
    }

    @Override
    public String getJob() {
      return this.job;
    }

    @Override
    public String getBuildUrl() {
      return this.buildUrl;
    }

    @Override
    public int getBuildNumber() {
      return this.buildNumber;
    }

    @Override
    public String getBuildName() {
      return this.buildName;
    }

    @Override
    public boolean cancel() {
      BuildState buildState;

      buildState = this.getBuildState();

      if (buildState == BuildState.QUEUED) {
        int indexSlash;

        // The queue item URL looks like "https://server/jenkins/queue/item/####/" and we
        // need to generate a URL such as
        // "https://server/jenkins/queue/cancelItem?id=####".
        indexSlash = this.queueItemUrl.lastIndexOf('/', this.queueItemUrl.length() - 2);

//??? Probably returns a 404 due to bug https://issues.jenkins-ci.org/browse/JENKINS-21311. Need to ignore.
        DefaultJenkinsClientImpl.this.post(this.queueItemUrl.substring(0, indexSlash - 4) + "cancelItem?id=" + this.queueItemUrl.substring(indexSlash + 1, this.queueItemUrl.length() - 1));

        return this.getBuildState() == BuildState.CANCELLED;
      } else if (buildState == BuildState.RUNNING) {
        DefaultJenkinsClientImpl.this.post(this.buildUrl + "stop");

        return this.getBuildState() == BuildState.ABORTED;
      }

      return false;
    }

    @Override
    public String getNextConsoleChunk() {
      URL url;
      HttpURLConnection httpUrlConnection;
      int responseCode;

      if (!this.buildStatePrevious.isOutOfQueue() && !this.getBuildState().isOutOfQueue()) {
        return null;
      }

      if (this.nextConsoleStart == -1) {
        return null;
      }

      // Because we need to handle the special headers X-Text-Size and X-More-Data, we
      // cannot use the generic methods in JenkinsClient.

      try {
        url = new URL(this.buildUrl + "logText/progressiveText?start=" + this.nextConsoleStart);

        httpUrlConnection = (HttpURLConnection)url.openConnection();

        DefaultJenkinsClientImpl.this.setBasicAuthBase64(httpUrlConnection);

        httpUrlConnection.setRequestMethod("GET");
        httpUrlConnection.setInstanceFollowRedirects(false);

        httpUrlConnection.connect();

        responseCode = httpUrlConnection.getResponseCode();

        if (responseCode == 200) {
          StringBuilder stringBuilderOutput;
          Reader reader;
          char[] arrayCharOutput;
          int nbCharOutputRead;

          stringBuilderOutput = new StringBuilder();
          reader = new InputStreamReader(httpUrlConnection.getInputStream(), "UTF-8");
          arrayCharOutput = new char[1024];

          while ((nbCharOutputRead = reader.read(arrayCharOutput)) != -1) {
            stringBuilderOutput.append(arrayCharOutput, 0, nbCharOutputRead);
          }

          reader.close();

          if (httpUrlConnection.getHeaderField("X-More-Data").equals("false")) {
            this.nextConsoleStart = -1;
          } else {
            this.nextConsoleStart = Integer.parseInt(httpUrlConnection.getHeaderField("X-Text-Size"));
          }

          return stringBuilderOutput.toString();
        } else {
          DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

          throw new HttpStatusException("GET " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    @Override
    public String getFullConsole() {
      if (!this.buildStatePrevious.isOutOfQueue() && !this.getBuildState().isOutOfQueue()) {
        return null;
      }

      return DefaultJenkinsClientImpl.this.getForText(this.buildUrl + "consoleText");
    }
  }

  /**
   * Default constructor.
   */
  public DefaultJenkinsClientImpl() {
  }

  @Override
  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  @Override
  public void setUser(String user) {
    this.user = user;

    // Once set, the user is not expected to be reset. But just in case, we reset the
    // cached base-64 basic authentication string.
    this.basicAuthBase64 = null;
  }

  @Override
  public void setPassword(String password) {
    this.password = password;

    // Once set, the password is not expected to be reset. But just in case, we reset the
    // cached base-64 basic authentication string.
    this.basicAuthBase64 = null;
  }

  @Override
  public boolean validateCredentials() {
    try {
      // XML is actually returned, but we are not interested in the output. We therefore
      // avoid parsing it as XML.
      this.getForText(this.baseUrl + "/api/xml");

      return true;
    } catch (HttpStatusException hse) {
      // 401 means "invalid credentials".
      if (hse.getStatusCode() == 401) {
        return false;
      } else {
        throw hse;
      }
    }
  }

  @Override
  public ItemType getItemType(String item) {
    Document document;

    try {
      document = this.getForXml(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(item) + "/api/xml");

      return document.getDocumentElement().getNodeName().equals("folder") ? ItemType.FOLDER : ItemType.NOT_FOLDER;
    } catch (HttpStatusException hse) {
      if (hse.getStatusCode() == 404) {
        return null;
      }

      throw hse;
    }
  }

  @Override
  public boolean deleteItem(String item) {
    try {
      this.post(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(item) + "/doDelete");

      // It seems like Jenkins returns 302 (found) when the job is properly deleted.
      // this is handled below. We therefore should never get here (2xx status codes).
      // But we avoid being too strict.
      return true;
    } catch (HttpStatusException hse) {
      int statusCode;

      statusCode = hse.getStatusCode();

      if (statusCode == 302) {
        return true;
      } else if (statusCode == 404) {
        return false;
      } else {
        throw hse;
      }
    }
  }

  @Override
  public void createUpdateJobFromTemplate(String template, String job, Map<String, String> mapTemplateParam) {
    URL url;
    HttpURLConnection httpUrlConnection;
    OutputStream outputStream;
    int responseCode;

    try {
      url = new URL(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(template) + "/instantiate?job=" + URLEncoder.encode(job, "UTF-8"));
      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("POST");
      httpUrlConnection.setInstanceFollowRedirects(false);
      httpUrlConnection.setDoOutput(true);
      httpUrlConnection.setRequestProperty("Content-Type", "text/xml");

      httpUrlConnection.connect();

      outputStream = httpUrlConnection.getOutputStream();

      outputStream.write("<values>".getBytes("US-ASCII"));

      for (Map.Entry<String, String> mapEntry: mapTemplateParam.entrySet()) {
        byte[] arrayByteKey;

        arrayByteKey = mapEntry.getKey().getBytes("US-ASCII");
        outputStream.write('<');
        outputStream.write(arrayByteKey);
        outputStream.write('>');
        outputStream.write(mapEntry.getValue().getBytes("US-ASCII"));
        outputStream.write("</".getBytes("US-ASCII"));
        outputStream.write(arrayByteKey);
        outputStream.write('>');
      }

      outputStream.write("</values>".getBytes("US-ASCII"));

      outputStream.close();

      responseCode = httpUrlConnection.getResponseCode();

      DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

      if (responseCode != 200) {
        throw new HttpStatusException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  @Override
  public void createJob(String job, Reader readerConfig) {
    int indexJobName;
    String folderName;
    String jobName;
    URL url;
    HttpURLConnection httpUrlConnection;
    OutputStream outputStream;
    int nbCharConfigRead;
    char[] arrayCharConfig;
    int responseCode;

    try {
      indexJobName = job.lastIndexOf('/');

      if (indexJobName == -1) {
        folderName = "";
        jobName = job;
      } else {
        folderName = job.substring(0, indexJobName);
        jobName = job.substring(indexJobName + 1);
      }

      url = new URL(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(folderName) + "/createItem?name=" + URLEncoder.encode(jobName, "UTF-8"));
      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("POST");
      httpUrlConnection.setInstanceFollowRedirects(false);
      httpUrlConnection.setDoOutput(true);
      httpUrlConnection.setRequestProperty("Content-Type", "application/xml");

      httpUrlConnection.connect();

      outputStream = httpUrlConnection.getOutputStream();

      arrayCharConfig = new char[1024];

      while ((nbCharConfigRead = readerConfig.read(arrayCharConfig)) != -1) {
        outputStream.write((new String(arrayCharConfig, 0, nbCharConfigRead)).getBytes("UTF-8"));
      }

      outputStream.close();

      responseCode = httpUrlConnection.getResponseCode();

      DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

      if (responseCode != 200) {
        throw new HttpStatusException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  @Override
  public void updateJob(String job, Reader readerConfig) {
    URL url;
    HttpURLConnection httpUrlConnection;
    OutputStream outputStream;
    int nbCharConfigRead;
    char[] arrayCharConfig;
    int responseCode;

    try {
      url = new URL(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(job) + "/config.xml");
      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("POST");
      httpUrlConnection.setInstanceFollowRedirects(false);
      httpUrlConnection.setDoOutput(true);
      httpUrlConnection.setRequestProperty("Content-Type", "application/xml");

      httpUrlConnection.connect();

      outputStream = httpUrlConnection.getOutputStream();

      arrayCharConfig = new char[1024];

      while ((nbCharConfigRead = readerConfig.read(arrayCharConfig)) != -1) {
        outputStream.write((new String(arrayCharConfig, 0, nbCharConfigRead)).getBytes("UTF-8"));
      }

      outputStream.close();

      responseCode = httpUrlConnection.getResponseCode();

      DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

      if (responseCode != 200) {
        throw new HttpStatusException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  @Override
  public void createUpdateJob(String job, Reader readerConfig) {
    ItemType itemType;

    itemType = this.getItemType(job);

    if (itemType != null) {
      if (itemType == ItemType.FOLDER) {
        throw new RuntimeException("Item " + job + " exists and is a folder. It cannot be updated as a job.");
      }

      this.updateJob(job, readerConfig);
    } else {
      this.createJob(job, readerConfig);
    }
  }

  @Override
  public Build build(String job, Map<String, String> mapBuildParam) {
    boolean indParams;
    URL url;
    HttpURLConnection httpUrlConnection;
    StringBuilder stringBuilderParams = null;
    int responseCode;

    try {
      indParams = ((mapBuildParam != null) && !mapBuildParam.isEmpty());

      if (indParams) {
        url = new URL(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(job) + "/buildWithParameters");

        stringBuilderParams = new StringBuilder();

        for (Map.Entry<String, String> mapEntry: mapBuildParam.entrySet()) {
          stringBuilderParams.append(mapEntry.getKey());
          stringBuilderParams.append('=');
          stringBuilderParams.append(URLEncoder.encode(mapEntry.getValue(), "UTF-8"));
          stringBuilderParams.append('&');
        }

        stringBuilderParams.setLength(stringBuilderParams.length() - 1);
      } else {
        url = new URL(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(job) + "/build");
      }

      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("POST");
      httpUrlConnection.setInstanceFollowRedirects(false);

      if (indParams) {
        httpUrlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        httpUrlConnection.setDoOutput(true);
      }

      httpUrlConnection.connect();

      if (indParams) {
        OutputStream outputStream;

        outputStream = httpUrlConnection.getOutputStream();
        outputStream.write(stringBuilderParams.toString().getBytes());
        outputStream.close();
      }

      responseCode = httpUrlConnection.getResponseCode();

      DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

      // It seems like Jenkins returns 302 (found) when the job is properly deleted. We
      // test for 200 also to be clean.
      if ((responseCode == 200) || (responseCode == 201)) {
        return new DefaultBuildImpl(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(job), job, httpUrlConnection.getHeaderField("Location"));
      } else {
        throw new HttpStatusException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  @Override
  public boolean isFolderEmpty(String folder) {
    Document document;

    document = this.getForXml(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(folder) + "/api/xml");

    return document.getElementsByTagName("job").getLength() != 0;

  }

  @Override
  public boolean createSimpleFolder(String folder) {
    ItemType itemType;
    int indexFolderName;
    String parentFolderName;
    String folderName;
    URL url;
    HttpURLConnection httpUrlConnection;
    OutputStream outputStream;
    int responseCode;

    itemType = this.getItemType(folder);

    if (itemType != null) {
      if (itemType == ItemType.FOLDER) {
        return false;
      } else {
        throw new RuntimeException("Item " + folder + " already exists but is not a folder.");
      }
    }

    try {
      indexFolderName = folder.lastIndexOf('/');

      if (indexFolderName == -1) {
        parentFolderName = "";
        folderName = folder;
      } else {
        parentFolderName = folder.substring(0, indexFolderName);
        folderName = folder.substring(indexFolderName + 1);
      }

      url = new URL(this.baseUrl + DefaultJenkinsClientImpl.convertItemToPath(parentFolderName) + "/createItem?name=" + URLEncoder.encode(folderName, "UTF-8"));
      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("POST");
      httpUrlConnection.setInstanceFollowRedirects(false);
      httpUrlConnection.setDoOutput(true);
      httpUrlConnection.setRequestProperty("Content-Type", "application/xml");

      httpUrlConnection.connect();

      outputStream = httpUrlConnection.getOutputStream();

      // We try to provide an as simple as possible folder config.xml, expecting Jenkins
      // to complete missing information with appropriate defaults.
      outputStream.write("<com.cloudbees.hudson.plugins.folder.Folder plugin=\"cloudbees-folder\"/>".getBytes("UTF-8"));

      outputStream.close();

      responseCode = httpUrlConnection.getResponseCode();

      DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

      if (responseCode != 200) {
        throw new HttpStatusException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }

      return true;
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Sets the base-64 basic authentication string in a HttpURLConnection.
   * <p>
   * Used by all methods which prepare an HttpURLConnection to submit a request to
   * Jenkins.
   * <p>
   * If the user is not available, it is not set, in which case Jenkins is accessed
   * anonymously.
   *
   * @param httpUrlConnection See description.
   */
  private void setBasicAuthBase64(HttpURLConnection httpUrlConnection) {
    if (this.basicAuthBase64 == null && this.user != null) {
      try {
        this.basicAuthBase64 = DatatypeConverter.printBase64Binary((this.user + ':' + this.password).getBytes("US-ASCII"));
      } catch (UnsupportedEncodingException usee) {
        throw new RuntimeException(usee);
      }
    }

    if (this.basicAuthBase64 != null) {
      httpUrlConnection.setRequestProperty("Authorization",  "Basic " + this.basicAuthBase64);
    }
  }

  /**
   * Convenience method to issue a GET request on a URL, expect an XML document and
   * return it.
   *
   * @param stringUrl URL.
   * @return Document.
   */
  private Document getForXml(String stringUrl) {
    URL url;
    HttpURLConnection httpUrlConnection;
    int responseCode;

    try {
      url = new URL(stringUrl);

      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("GET");
      httpUrlConnection.setInstanceFollowRedirects(false);

      httpUrlConnection.connect();

      responseCode = httpUrlConnection.getResponseCode();

      if (responseCode == 200) {
        InputStream inputStream;
        DocumentBuilderFactory documentBuilderFactory;
        DocumentBuilder documentBuilder;
        Document document;

        inputStream = httpUrlConnection.getInputStream();
        documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilder = documentBuilderFactory.newDocumentBuilder();
        document = documentBuilder.parse(inputStream);
        inputStream.close();
        return document;
      } else {
        DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

        throw new HttpStatusException("GET " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException | ParserConfigurationException | SAXException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Convenience method to issue a GET request on a URL, expect text output and
   * return it.
   *
   * @param stringUrl URL.
   * @return String.
   */
  private String getForText(String stringUrl) {
    URL url;
    HttpURLConnection httpUrlConnection;
    int responseCode;

    try {
      url = new URL(stringUrl);

      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("GET");
      httpUrlConnection.setInstanceFollowRedirects(false);

      httpUrlConnection.connect();

      responseCode = httpUrlConnection.getResponseCode();

      if (responseCode == 200) {
        StringBuilder stringBuilderOutput;
        Reader reader;
        char[] arrayCharOutput;
        int nbCharOutputRead;

        stringBuilderOutput = new StringBuilder();
        reader = new InputStreamReader(httpUrlConnection.getInputStream(), "UTF-8");
        arrayCharOutput = new char[1024];

        while ((nbCharOutputRead = reader.read(arrayCharOutput)) != -1) {
          stringBuilderOutput.append(arrayCharOutput, 0, nbCharOutputRead);
        }

        reader.close();

        return stringBuilderOutput.toString();
      } else {
        DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

        throw new HttpStatusException("GET " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Convenience method to ussue a POST request on a URL, with no expected output.
   *
   * @param stringUrl URL.
   */
  private void post(String stringUrl) {
    URL url;
    HttpURLConnection httpUrlConnection;
    int responseCode;

    try {
      url = new URL(stringUrl);

      httpUrlConnection = (HttpURLConnection)url.openConnection();

      this.setBasicAuthBase64(httpUrlConnection);

      httpUrlConnection.setRequestMethod("POST");
      httpUrlConnection.setInstanceFollowRedirects(false);

      httpUrlConnection.connect();

      responseCode = httpUrlConnection.getResponseCode();

      DefaultJenkinsClientImpl.flushInputErrorStreams(httpUrlConnection);

      if (responseCode != 200) {
        throw new HttpStatusException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.', responseCode);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Converts an item (full name) to an item path within Jenkins (required to build
   * various URLs).
   *
   * @param item Item (full name).
   * @return Item path.
   */
  private static String convertItemToPath(String item) {
    return "/job/" + item.replaceAll("/", "/job/");
  }

  /**
   * Flush the input and error streams, as recommended by the document for
   * HttpURLConnection when the response body is not required but may have been
   * streamed by the server.
   *
   * @param httpUrlConnection HttpURLConnection.
   */
  private static void flushInputErrorStreams(HttpURLConnection httpUrlConnection) {
    int responseCode;
    InputStream inputStream;

    try {
      responseCode = httpUrlConnection.getResponseCode();

      if ((responseCode >= 200) && (responseCode < 300)) {
        inputStream = httpUrlConnection.getInputStream();

        if (inputStream != null) {
          inputStream.skip(Integer.MAX_VALUE);
          inputStream.close();
        }
      }

      if (httpUrlConnection.getResponseCode() != 200) {
        inputStream = httpUrlConnection.getErrorStream();

        if (inputStream != null) {
          inputStream.skip(Integer.MAX_VALUE);
          inputStream.close();
        }
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  public static void main(String[] args) {
/*
		JenkinsClient jc;
		boolean b;
		Map<String, String> mapParams;

		try {
			jc = new DefaultJenkinsClientImpl();
			jc.setBaseUrl("https://");
			jc.setUser("");
			jc.setPassword("");

//			b = jc.isJobExist("");
//			b = jc.deleteJob("");
//			jc.createJob("", new FileReader("C:\\Temp\\config.xml"));
//			jc.updateJob("", new FileReader("C:\\Temp\\config.xml"));
//			jc.createUpdateJob("", new FileReader("C:\\Temp\\config.xml"));

//			mapParams = new HashMap<String, String>();

//			mapParams.put("URL_DEPOT_GIT", "");
//			mapParams.put("BRANCHE", "");
//			mapParams.put("GROUP_ID", "");
//			mapParams.put("ARTIFACT_ID", "test");
//			mapParams.put("JDK", "JDK 1.7.0");
//			mapParams.put("MAVEN", "Maven 3.2");

//			b = jc.deleteJob("");
//			jc.createUpdateJobFromTemplate("", "", mapParams);
//			jc.createUpdateJobFromTemplate("", "", mapParams);
			Build build = jc.build("", null);

			BuildState buildState;

			do {
				buildState = build.getBuildState();

				System.out.println("Job URL: " + build.getJobUrl());
				System.out.println("Job: " + build.getJob());
				System.out.println("Build URL: " + build.getBuildUrl());
				System.out.println("Build number: " + build.getBuildNumber());
				System.out.println("Build name: " + build.getBuildName());
				System.out.println("State: " + buildState);

				System.out.println("##########");
			} while (buildState == BuildState.RUNNING || buildState == BuildState.QUEUED);

		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		System.out.println("Done.");
*/
  }
}
