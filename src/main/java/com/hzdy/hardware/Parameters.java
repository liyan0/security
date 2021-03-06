package com.hzdy.hardware;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;

public class Parameters {
	static final String PARAMETER_SYSTEM_PREFIX = "javamelody.";
	static final File TEMPORARY_DIRECTORY = new File(System.getProperty("java.io.tmpdir"));
	public static final String JAVA_VERSION = System.getProperty("java.version");
	static final String JAVAMELODY_VERSION = getJavaMelodyVersion();
	// default monitoring-path is "/monitoring" in the http URL
	private static final String DEFAULT_MONITORING_PATH = "/monitoring";
	// résolution (ou pas) par défaut en s de stockage des valeurs dans les
	// fichiers RRD
	private static final int DEFAULT_RESOLUTION_SECONDS = 60;
	// stockage des fichiers RRD de JRobin dans le répertoire
	// temp/javamelody/<context> par défaut
	private static final String DEFAULT_DIRECTORY = "javamelody";
	// nom du fichier stockant les applications et leurs urls dans le répertoire
	// de stockage
	private static final String COLLECTOR_APPLICATIONS_FILENAME = "applications.properties";
	private static Map<String, List<URL>> urlsByApplications;

	private static FilterConfig filterConfig;
	private static ServletContext servletContext;
	private static String lastConnectUrl;
	private static Properties lastConnectInfo;
	private static boolean dnsLookupsDisabled;

	private Parameters() {
		super();
	}

	static void initialize(FilterConfig config) {
		filterConfig = config;
		if (config != null) {
			final ServletContext context = config.getServletContext();
			initialize(context);
		}
	}

	static void initialize(ServletContext context) {
		if ("1.6".compareTo(JAVA_VERSION) > 0) {
			throw new IllegalStateException("La version java doit être 1.6 au minimum et non " + JAVA_VERSION);
		}
		servletContext = context;

		dnsLookupsDisabled = Boolean.parseBoolean(getParameter(Parameter.DNS_LOOKUPS_DISABLED));
	}

	static void initJdbcDriverParameters(String connectUrl, Properties connectInfo) {
		Parameters.lastConnectUrl = connectUrl;
		Parameters.lastConnectInfo = connectInfo;
	}

	/**
	 * @return Contexte de servlet de la webapp, soit celle monitorée ou soit
	 *         celle de collecte.
	 */
	static ServletContext getServletContext() {
		assert servletContext != null;
		return servletContext;
	}

	static String getLastConnectUrl() {
		return lastConnectUrl;
	}

	static Properties getLastConnectInfo() {
		return lastConnectInfo;
	}

	static File getCollectorApplicationsFile() {
		return new File(getStorageDirectory(""), COLLECTOR_APPLICATIONS_FILENAME);
	}

	static String getMonitoringPath() {
		final String parameterValue = getParameter(Parameter.MONITORING_PATH);
		if (parameterValue == null) {
			return DEFAULT_MONITORING_PATH;
		}
		return parameterValue;
	}

	public static String getHostName() {
		if (dnsLookupsDisabled) {
			return "localhost";
		}

		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (final UnknownHostException ex) {
			return "unknown";
		}
	}

	public static String getHostAddress() {
		if (dnsLookupsDisabled) {
			return "127.0.0.1"; // NOPMD
		}

		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (final UnknownHostException ex) {
			return "unknown";
		}
	}
	public static String getResourcePath(String fileName) {
		final Class<Parameters> classe = Parameters.class;
		final String packageName = classe.getName().substring(0,
				classe.getName().length() - classe.getSimpleName().length() - 1);
		return '/' + packageName.replace('.', '/') + "/resource/" + fileName;
	}

	static int getResolutionSeconds() {
		final String param = getParameter(Parameter.RESOLUTION_SECONDS);
		if (param != null) {
			// lance une NumberFormatException si ce n'est pas un nombre
			final int result = Integer.parseInt(param);
			if (result <= 0) {
				throw new IllegalStateException(
						"The parameter resolution-seconds should be > 0 (between 60 and 600 recommended)");
			}
			return result;
		}
		return DEFAULT_RESOLUTION_SECONDS;
	}

	static File getStorageDirectory(String application) {
		final String param = getParameter(Parameter.STORAGE_DIRECTORY);
		final String dir;
		if (param == null) {
			dir = DEFAULT_DIRECTORY;
		} else {
			dir = param;
		}
	
		final String directory;
		if (!dir.isEmpty() && new File(dir).isAbsolute()) {
			directory = dir;
		} else {
			directory = TEMPORARY_DIRECTORY.getPath() + '/' + dir;
		}
		if (servletContext != null) {
			return new File(directory + '/' + application);
		}
		return new File(directory);
	}

	/**
	 * Booléen selon que le paramètre no-database vaut true.
	 * 
	 * @return boolean
	 */
	static boolean isNoDatabase() {
		return Boolean.parseBoolean(Parameters.getParameter(Parameter.NO_DATABASE));
	}

	/**
	 * Booléen selon que le paramètre system-actions-enabled vaut true.
	 * 
	 * @return boolean
	 */
	static boolean isSystemActionsEnabled() {
		final String parameter = Parameters.getParameter(Parameter.SYSTEM_ACTIONS_ENABLED);
		return parameter == null || Boolean.parseBoolean(parameter);
	}

	/**
	 * Retourne false si le paramètre displayed-counters n'a pas été défini ou
	 * si il contient le compteur dont le nom est paramètre, et retourne true
	 * sinon (c'est-à-dire si le paramètre displayed-counters est défini et si
	 * il ne contient pas le compteur dont le nom est paramètre).
	 * 
	 * @param counterName
	 *            Nom du compteur
	 * @return boolean
	 */
	static boolean isCounterHidden(String counterName) {
		final String displayedCounters = getParameter(Parameter.DISPLAYED_COUNTERS);
		if (displayedCounters == null) {
			return false;
		}
		for (final String displayedCounter : displayedCounters.split(",")) {
			final String displayedCounterName = displayedCounter.trim();
			if (counterName.equalsIgnoreCase(displayedCounterName)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return Nom de l'application courante et nom du sous-répertoire de
	 *         stockage dans une application monitorée.
	 */
	static String getCurrentApplication() {
		// use explicitly configured application name (if configured)
		final String applicationName = getParameter(Parameter.APPLICATION_NAME);
		if (applicationName != null) {
			return applicationName;
		}
		if (servletContext != null) {
			// Le nom de l'application et donc le stockage des fichiers est dans
			// le sous-répertoire
			// ayant pour nom le contexte de la webapp et le nom du serveur
			// pour pouvoir monitorer plusieurs webapps sur le même serveur et
			// pour pouvoir stocker sur un répertoire partagé entre plusieurs
			// serveurs
			return getContextPath(servletContext) + '_' + getHostName();
		}
		return null;
	}

	static String getContextPath(ServletContext context) {
		if (context.getMajorVersion() == 2 && context.getMinorVersion() >= 5 || context.getMajorVersion() > 2) {
			return context.getContextPath();
		}
		final URL webXmlUrl;
		try {
			webXmlUrl = context.getResource("/WEB-INF/web.xml");
		} catch (final MalformedURLException e) {
			throw new IllegalStateException(e);
		}
		String contextPath = webXmlUrl.toExternalForm();
		contextPath = contextPath.substring(0, contextPath.indexOf("/WEB-INF/web.xml"));
		final int indexOfWar = contextPath.indexOf(".war");
		if (indexOfWar > 0) {
			contextPath = contextPath.substring(0, indexOfWar);
		}
		if (contextPath.startsWith("jndi:/localhost")) {
			contextPath = contextPath.substring("jndi:/localhost".length());
		}
		final int lastIndexOfSlash = contextPath.lastIndexOf('/');
		if (lastIndexOfSlash != -1) {
			contextPath = contextPath.substring(lastIndexOfSlash);
		}
		return contextPath;
	}

	private static String getJavaMelodyVersion() {
		final InputStream inputStream = Parameters.class.getResourceAsStream("/JAVAMELODY-VERSION.properties");
		if (inputStream == null) {
			return null;
		}

		final Properties properties = new Properties();
		try {
			try {
				properties.load(inputStream);
				return properties.getProperty("version");
			} finally {
				inputStream.close();
			}
		} catch (final IOException e) {
			return e.toString();
		}
	}

	/**
	 * Recherche la valeur d'un paramètre qui peut être défini par ordre de
	 * priorité croissant : - dans les paramètres d'initialisation du filtre
	 * (fichier web.xml dans la webapp) - dans les paramètres du contexte de la
	 * webapp avec le préfixe "javamelody." (fichier xml de contexte dans
	 * Tomcat) - dans les variables d'environnement du système d'exploitation
	 * avec le préfixe "javamelody." - dans les propriétés systèmes avec le
	 * préfixe "javamelody." (commande de lancement java)
	 * 
	 * @param parameter
	 *            Enum du paramètre
	 * @return valeur du paramètre ou null si pas de paramètre défini
	 */
	public static String getParameter(Parameter parameter) {
		assert parameter != null;
		final String name = parameter.getCode();
		return getParameterByName(name);
	}

	static String getParameterByName(String parameterName) {
		assert parameterName != null;
		final String globalName = PARAMETER_SYSTEM_PREFIX + parameterName;
		String result = System.getProperty(globalName);
		if (result != null) {
			return result;
		}
		if (servletContext != null) {
			result = servletContext.getInitParameter(globalName);
			if (result != null) {
				return result;
			}
			// issue 463: in a ServletContextListener, it's also possible to
			// call servletContext.setAttribute("javamelody.log", "true"); for
			// example
			final Object attribute = servletContext.getAttribute(globalName);
			if (attribute instanceof String) {
				return (String) attribute;
			}
		}
		if (filterConfig != null) {
			result = filterConfig.getInitParameter(parameterName);
			if (result != null) {
				return result;
			}
		}
		return null;
	}
}
