/**
 * Builds a custom Jenkins WAR using <a href="https://github.com/jenkinsci/custom-war-packager">Custom WAR Packager</a>.
 * The method supports both YAML BOM and Custom WAR Packager YAM as an input.
 *
 * @param metadataFile Path to the metadata file.
 * @param outputWAR Path, to which the output WAR file should be written
 * @param outputBOM Path, to which the output BOM file should be written.
 *                  More info about BOMs: https://github.com/jenkinsci/jep/pull/92
 * @param mvnSettingsFile
 *                  Maven Settings file to be used during the build.
 *                  If {@code null}, it will be determined automatically.
 * @return nothing
 */
def build(String metadataFile, String outputWAR, String outputBOM, String mvnSettingsFile) {
    def metadata = readYaml(file: metadataFile)
    def bomFilePath = metadata.packaging.bom ?: null
    def environment = metadata.packaging.environment
    def jdk = metadata.packaging.jdk ?: "8"
    //TODO: replace by a stable release once ready, better name?
    def cwpVersion = metadata.packaging.cwpVersion ?: "0.1-alpha-5-20180503.142207-7"

    // Resolve the CWP configuration file
    def configFilePath = null
    if (metadata.packaging.config != null) {
        configFilePath = "${pwd tmp: true}/packager-config.yml"
        sh "rm -rf ${configFilePath}"
        writeYaml(file: configFilePath, data: metadata.packaging.config)
    } else if (metadata.packaging.configFile) {
        configFilePath = metadata.packaging.configFile
    } else {
        error "packaging.config or packaging.configFile must be defined"
    }

    // Resolve the Maven configuration file if not passed
    if (mvnSettingsFile == null) {
        mvnSettingsFile = "${pwd tmp: true}/settings-azure.xml"
    }

    // In order to run packager, we require artifactId and version for packaging
    def version = null
    def artifactId = null
    if (bomFilePath == null) {
        def files = findFiles(glob: "bom.yml")
        if (files != null && files.length > 0) {
            echo "BOM file is not explicitly defined, but there is bom.yml in the root. Using it"
            bomFilePath = files[0]
        }
    }
    if (bomFilePath != null ) {
        def bom = readYaml(file: bomFilePath)
        //TODO: adjust if parameters are made optional
        if (bom.metadata.labels != null) {
            version = bom.metadata.labels.version
            artifactId = bom.metadata.labels.artifactId
        }
    }

    if ((version == null || artifactId == null) && configFilePath != null) { // Try config YML
        def config = readYaml(file: configFilePath)
        if (config.bundle != null && artifactId == null) {
            artifactId = config.bundle.artifactId
        }
        if (config.buildSettings != null && version == null) {
            version = config.buildSettings.version
        }
    }

    if (version == null) {
        version = "1.0-SNAPSHOT"
    }
    if (artifactId == null) {
        artifactId = "jenkins-war"
    }

    // Warm-up
    // TODO: Remove once JENKINS-51070 is fixed
    List<String> mavenWarmup = [
        '--batch-mode', '--errors',
        "org.apache.maven.plugins:maven-dependency-plugin:3.0.2:get",
        "-Dartifact=io.jenkins.tools.custom-war-packager:custom-war-packager-cli:${cwpVersion}",
        "-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
    ]
    infra.runMaven(mavenWarmup, jdk, null, mvnSettingsFile)

    echo "Downloading Custom WAR Packager CLI ${cwpVersion}"
    List<String> mavenOptions = [
        '--batch-mode', '--errors',
        "com.googlecode.maven-download-plugin:download-maven-plugin:1.4.0:artifact",
        "-DgroupId=io.jenkins.tools.custom-war-packager",
        "-DartifactId=custom-war-packager-cli",
        "-Dclassifier=jar-with-dependencies",
        "-Dversion=${cwpVersion}",
        "-DoutputDirectory=${pwd()}",
        "-DoutputFileName=cwp-cli.jar",
        "-Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn"
    ]
    infra.runMaven(mavenOptions, jdk, null, mvnSettingsFile)

    def command = "java -jar cwp-cli.jar --batch-mode -configPath ${configFilePath} -version=${version}"
    if (environment != null) {
        command += " --environment ${environment}"
    }
    if (bomFilePath != null) {
        command += " --bomPath ${bomFilePath}"
    }
    if (mvnSettingsFile != null) {
        command += " -mvnSettingsFile=${mvnSettingsFile}"
    }
    infra.runWithMaven(command)

    dir("tmp/output/target/") {
        archiveArtifacts artifacts: "${artifactId}-${version}.war"
        archiveArtifacts artifacts: "${artifactId}-${version}.bom.yml"
        sh "cp ${artifactId}-${version}.war ${outputWAR}"
        sh "cp ${artifactId}-${version}.bom.yml ${outputBOM}"
    }
}
