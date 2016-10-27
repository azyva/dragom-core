/*
 * Copyright 2015 AZYVA INC.
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

package org.azyva.dragom.jenkins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

import javax.xml.bind.DatatypeConverter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


/**
 * Jenkins client class which interacts with Jenkins through its REST API.
 * <p>
 * This class implements the REST calls that are useful for Dragom. It is not
 * meant to be a full-featured implementation of the Jenkins API in Java.
 *
 * @author David Raymond
 */
public class JenkinsClient {
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
	 * Build states.
	 */
	enum BuildState {
		/**
		 * Build is queued and not running yet.
		 */
		QUEUED,

		/**
		 * Build has been cancelled while it was queued.
		 * <p>
		 * This is similar to ABORTED, except that the build was never RUNNING.
		 */
		CANCELLED,

		/**
		 * Build is running.
		 */
		RUNNING,

		/**
		 * Build has been aborted while it was running.
		 * <p>
		 * This is similar to CANCELLED, except that the build was RUNNING.
		 */
		ABORTED,

		/**
		 * Build is completed and is FAILED.
		 */
		FAILED,

		/**
		 * Build is completed and is UNSTABLE.
		 */
		UNSTABLE,

		/**
		 * Build completed with SUCCESS.
		 */
		SUCCESS;

		/**
		 * @return Indicates if the build is out of the queue (is running or completed).
		 */
		boolean isOutOfQueue() {
			return (this != QUEUED) && (this != CANCELLED);
		}

		/**
		 * @return Indicates if the build is completed.
		 */
		boolean isCompleted() {
			return (this == ABORTED) || (this == FAILED) || (this == UNSTABLE) || (this == SUCCESS);
		}
	}

	/**
	 * Represents a build.
	 * <p>
	 * Instances of this class are created by {@link JenkinsClient#build).
	 * <p>
	 * Allows getting information about the build while it progresses to completion.
	 */
	public class Build {
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
		 * Constructor.
		 *
		 * @param jobUrl Job URL.
		 * @param job Job (full name).
		 * @param queueItemUrl Queue item URL.
		 */
		private Build(String jobUrl, String job, String queueItemUrl) {
			this.jobUrl = jobUrl;
			this.job = job;
			this.queueItemUrl = queueItemUrl;
			this.buildStatePrevious = BuildState.QUEUED;
		}

		/**
		 * Updates and returns the BuildState.
		 *
		 * @return See description.
		 */
		public BuildState getBuildState() {
			Document document;
			NodeList nodeList;

			if (this.buildStatePrevious == BuildState.QUEUED) {
				document = JenkinsClient.this.getForXml(this.queueItemUrl + "api/xml");

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
				document = JenkinsClient.this.getForXml(this.buildUrl + "api/xml");

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

		/**
		 * @return Job URL.
		 */
		public String getJobUrl() {
			return this.jobUrl;
		}

		/**
		 * @return Job (full name).
		 */
		public String getJob() {
			return this.job;
		}

		/**
		 * @return Build URL.
		 */
		public String getBuildUrl() {
			return this.buildUrl;
		}

		/**
		 * @return Build number.
		 */
		public int getBuildNumber() {
			return this.buildNumber;
		}

		/**
		 * @return Build (display) name.
		 */
		public String getBuildName() {
			return this.buildName;
		}

		/**
		 * Cancels or aborts the build, depending on whether its {@link BuildState} is
		 * {@link BuildState#QUEUED} or {@link BuildState#RUNNING}.
		 * <p>
		 * A build in any other BuildState cannot be cancelled.
		 */
		public void cancel() {
???
		}

		/**
		 * @return Returns the next chunk of console output or null if not
		 *   {@link BuildState#isOutOfQueue}.
		 */
		public String getNextConsoleChunk() {
			return null;
		}

		/**
		 * @return Complete console output.
		 * @return
		 */
		public String getFullConsole() {
			return null;
		}
	}

	/**
	 * Constructor.
	 *
	 * @param baseUrl Base URL of Jenkins.
	 * @param user User to use to connect to Jenkins.
	 * @param password Password.
	 */
	public JenkinsClient(String baseUrl, String user, String password) {
		this.baseUrl = baseUrl;
		this.user = user;
		this.password = password;
		this.basicAuthBase64 = DatatypeConverter.printBase64Binary((this.user + ':' + this.password).getBytes());
	}

	/**
	 * Verifies if a job exists.
	 *
	 * @param job Job (full name).
	 * @return See description.
	 */
	public boolean isJobExist(String job) {
		URL url;
		HttpURLConnection httpUrlConnection;
		int responseCode;

		try {
			url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(job) + "/config.xml");
			httpUrlConnection = (HttpURLConnection)url.openConnection();

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
			httpUrlConnection.setRequestMethod("GET");
			httpUrlConnection.setInstanceFollowRedirects(false);

			httpUrlConnection.connect();
			responseCode = httpUrlConnection.getResponseCode();

			JenkinsClient.flushInputErrorStreams(httpUrlConnection);

			if (responseCode == 200) {
				return true;
			} else if (responseCode == 404) {
				return false;
			} else {
				throw new RuntimeException("GET " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * Created a job from a template.
	 *
	 * @param template Template (full name).
	 * @param job New job (full name).
	 * @param mapParams Template parameters.
	 */
	public void createUpdateJobFromTemplate(String template, String job, Map<String, String> mapParams) {
		URL url;
		HttpURLConnection httpUrlConnection;
		OutputStream outputStream;
		int responseCode;

		try {
			url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(template) + "/instantiate?job=" + URLEncoder.encode(job, "UTF-8"));
			httpUrlConnection = (HttpURLConnection)url.openConnection();

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
			httpUrlConnection.setRequestMethod("POST");
			httpUrlConnection.setInstanceFollowRedirects(false);
			httpUrlConnection.setDoOutput(true);
			httpUrlConnection.setRequestProperty("Content-Type", "text/xml");

			httpUrlConnection.connect();

			outputStream = httpUrlConnection.getOutputStream();

			outputStream.write("<values>".getBytes());

			for (Map.Entry<String, String> mapEntry: mapParams.entrySet()) {
				byte[] arrayByteKey;

				arrayByteKey = mapEntry.getKey().getBytes();
				outputStream.write('<');
				outputStream.write(arrayByteKey);
				outputStream.write('>');
				outputStream.write(mapEntry.getValue().getBytes());
				outputStream.write("</".getBytes());
				outputStream.write(arrayByteKey);
				outputStream.write('>');
			}

			outputStream.write("</values>".getBytes());

			outputStream.close();

			responseCode = httpUrlConnection.getResponseCode();

			JenkinsClient.flushInputErrorStreams(httpUrlConnection);

			if (responseCode != 200) {
				throw new RuntimeException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * Creates a regular non-templatized job.
	 *
	 * @param job New job (full name).
	 * @param readerConfig Reader providing the configuration of the job.
	 */
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

			url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(folderName) + "/createItem?name=" + URLEncoder.encode(jobName, "UTF-8"));
			httpUrlConnection = (HttpURLConnection)url.openConnection();

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
			httpUrlConnection.setRequestMethod("POST");
			httpUrlConnection.setInstanceFollowRedirects(false);
			httpUrlConnection.setDoOutput(true);
			httpUrlConnection.setRequestProperty("Content-Type", "application/xml");

			httpUrlConnection.connect();

			outputStream = httpUrlConnection.getOutputStream();

			arrayCharConfig = new char[1024];

			while ((nbCharConfigRead = readerConfig.read(arrayCharConfig)) != -1) {
				outputStream.write((new String(arrayCharConfig, 0, nbCharConfigRead)).getBytes());
			}

			outputStream.close();

			responseCode = httpUrlConnection.getResponseCode();

			JenkinsClient.flushInputErrorStreams(httpUrlConnection);

			if (responseCode != 200) {
				throw new RuntimeException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * Updates a regular non-templatized job.
	 *
	 * @param job Job (full name).
	 * @param readerConfig Reader providing the configuration of the job.
	 */
	public void updateJob(String job, Reader readerConfig) {
		URL url;
		HttpURLConnection httpUrlConnection;
		OutputStream outputStream;
		int nbCharConfigRead;
		char[] arrayCharConfig;
		int responseCode;

		try {
			url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(job) + "/config.xml");
			httpUrlConnection = (HttpURLConnection)url.openConnection();

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
			httpUrlConnection.setRequestMethod("POST");
			httpUrlConnection.setInstanceFollowRedirects(false);
			httpUrlConnection.setDoOutput(true);
			httpUrlConnection.setRequestProperty("Content-Type", "application/xml");

			httpUrlConnection.connect();

			outputStream = httpUrlConnection.getOutputStream();

			arrayCharConfig = new char[1024];

			while ((nbCharConfigRead = readerConfig.read(arrayCharConfig)) != -1) {
				outputStream.write((new String(arrayCharConfig, 0, nbCharConfigRead)).getBytes());
			}

			outputStream.close();

			responseCode = httpUrlConnection.getResponseCode();

			JenkinsClient.flushInputErrorStreams(httpUrlConnection);

			if (responseCode != 200) {
				throw new RuntimeException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * Creates or updates a regular non-templatized job.
	 *
	 * @param job Job (full name).
	 * @param readerConfig Reader providing the configuration of the job.
	 */
	public void createUpdateJob(String job, Reader readerConfig) {
		if (this.isJobExist(job)) {
			this.updateJob(job, readerConfig);
		} else {
			this.createJob(job, readerConfig);
		}
	}

	/**
	 * Deletes a job.
	 *
	 * @param job Job (full name).
	 * @return Indicates if the job existed and was deleted.
	 */
	public boolean deleteJob(String job) {
		URL url;
		HttpURLConnection httpUrlConnection;
		int responseCode;

		try {
			url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(job) + "/doDelete");
			httpUrlConnection = (HttpURLConnection)url.openConnection();

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
			httpUrlConnection.setRequestMethod("POST");
			httpUrlConnection.setInstanceFollowRedirects(false);

			httpUrlConnection.connect();
			responseCode = httpUrlConnection.getResponseCode();

			JenkinsClient.flushInputErrorStreams(httpUrlConnection);

			// It seems like Jenkins returns 302 (found) when the job is properly deleted. We
			// test for 200 also to be clean.
			if ((responseCode == 200) || (responseCode == 302)) {
				return true;
			} else if (responseCode == 404) {
				return false;
			} else {
				throw new RuntimeException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * Triggers a build for a job.
	 *
	 * @param job Job (full name).
	 * @param mapParams Build parameters. Can be null.
	 * @return Build.
	 */
	public Build build(String job, Map<String, String> mapParams) {
		boolean indParams;
		URL url;
		HttpURLConnection httpUrlConnection;
		StringBuilder stringBuilderParams = null;
		int responseCode;

		try {
			indParams = ((mapParams != null) && !mapParams.isEmpty());

			if (indParams) {
				url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(job) + "/buildWithParameters");

				stringBuilderParams = new StringBuilder();

				for (Map.Entry<String, String> mapEntry: mapParams.entrySet()) {
					stringBuilderParams.append(mapEntry.getKey());
					stringBuilderParams.append('=');
					stringBuilderParams.append(URLEncoder.encode(mapEntry.getValue(), "UTF-8"));
					stringBuilderParams.append('&');
				}

				stringBuilderParams.setLength(stringBuilderParams.length() - 1);
			} else {
				url = new URL(this.baseUrl + JenkinsClient.convertJobToPath(job) + "/build");
			}

			httpUrlConnection = (HttpURLConnection)url.openConnection();

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
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

			JenkinsClient.flushInputErrorStreams(httpUrlConnection);

			// It seems like Jenkins returns 302 (found) when the job is properly deleted. We
			// test for 200 also to be clean.
			if ((responseCode == 200) || (responseCode == 201)) {
				return new Build(this.baseUrl + JenkinsClient.convertJobToPath(job), job, httpUrlConnection.getHeaderField("Location"));
			} else {
				throw new RuntimeException("POST " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
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

			httpUrlConnection.setRequestProperty("Authorization", "Basic " + this.basicAuthBase64);
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
				JenkinsClient.flushInputErrorStreams(httpUrlConnection);

				throw new RuntimeException("GET " + url.toString() + " returned " + responseCode + " - " + httpUrlConnection.getResponseMessage() + '.');
			}
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Converts a job (full name) to a job path within Jenkins (required to build
	 * various URLs).
	 *
	 * @param job Job (full name).
	 * @return Job path.
	 */
	private static String convertJobToPath(String job) {
		return "/job/" + job.replaceAll("/", "/job/");
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
		JenkinsClient jc;
		boolean b;
		Map<String, String> mapParams;

		try {
			jc = new JenkinsClient("https://sipl.dev.desjardins.com/jenkins", "dhb7535", "");
//			b = jc.isJobExist("assemblage");
//			b = jc.deleteJob("assemblage/ic/SoutienDev/TestSipl/test-dr2");
//			jc.createJob("assemblage/ic/SoutienDev/TestSipl/test-dr2", new FileReader("C:\\Temp\\config.xml"));
//			jc.updateJob("assemblage/ic/SoutienDev/TestSipl/test-dr2", new FileReader("C:\\Temp\\config.xml"));
//			jc.createUpdateJob("assemblage/ic/SoutienDev/TestSipl/test-dr2", new FileReader("C:\\Temp\\config.xml"));

//			mapParams = new HashMap<String, String>();

//			mapParams.put("URL_DEPOT_GIT", "https://sipl.desjardins.com/stash/scm/sout/test.git");
//			mapParams.put("BRANCHE", "test");
//			mapParams.put("GROUP_ID", "com.desjardins.soutien-dev.test-sipl");
//			mapParams.put("ARTIFACT_ID", "test");
//			mapParams.put("JDK", "JDK 1.7.0");
//			mapParams.put("MAVEN", "Maven 3.2");

//			b = jc.deleteJob("assemblage/ic/SoutienDev/TestSipl/test-dr3");
//			jc.createUpdateJobFromTemplate("assemblage/ic/modele-job-assemblage-ic-maven-1", "assemblage/ic/SoutienDev/TestSipl/test-dr3", mapParams);
//			jc.createUpdateJobFromTemplate("assemblage/ic/modele-job-assemblage-ic-maven-1", "assemblage/ic/SoutienDev/TestSipl/test-dr3", mapParams);
			Build build = jc.build("assemblage/ic/SoutienDev/TestSipl/test-dr2", null);

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

	}
}
