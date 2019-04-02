// Copyright 2018, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import oracle.kubernetes.operator.BaseTest;
import org.yaml.snakeyaml.Yaml;

/** Domain class with all the utility methods for a Domain. */
public class JRFDomain extends Domain {

  public JRFDomain(String inputYaml) throws Exception {
    // read input domain yaml to test
    this(TestUtils.loadYaml(inputYaml));
  }

  public JRFDomain(Map<String, Object> inputDomainMap) throws Exception {
    initialize(inputDomainMap);
    createPV();
    createSecret();
    generateInputYaml();
    callCreateDomainScript(userProjectsDir);
    // TODO: add load balancer later
    // createLoadBalancer();
  }

  private void initialize(Map<String, Object> inputDomainMap) throws Exception {
    domainMap = inputDomainMap;
    this.userProjectsDir = BaseTest.getUserProjectsDir();
    this.projectRoot = BaseTest.getProjectRoot();

    // copy samples to RESULT_DIR
    TestUtils.exec(
        "cp -rf " + BaseTest.getProjectRoot() + "/kubernetes/samples " + BaseTest.getResultDir());

    this.voyager =
        System.getenv("LB_TYPE") != null && System.getenv("LB_TYPE").equalsIgnoreCase("VOYAGER");
    if (System.getenv("INGRESSPERDOMAIN") != null) {
      INGRESSPERDOMAIN = new Boolean(System.getenv("INGRESSPERDOMAIN")).booleanValue();
    }

    domainMap.put("domainName", domainMap.get("domainUID"));

    // read sample domain inputs
    String sampleDomainInputsFile =
        "/samples/scripts/create-weblogic-domain/domain-home-on-pv/create-domain-inputs.yaml";
    if (domainMap.containsKey("domainHomeImageBase")) {
      sampleDomainInputsFile =
          "/samples/scripts/create-weblogic-domain/domain-home-in-image/create-domain-inputs.yaml";
    }
    Yaml dyaml = new Yaml();
    InputStream sampleDomainInputStream =
        new FileInputStream(new File(BaseTest.getResultDir() + sampleDomainInputsFile));
    logger.info(
        "loading domain inputs template file " + BaseTest.getResultDir() + sampleDomainInputsFile);
    Map<String, Object> sampleDomainMap = dyaml.load(sampleDomainInputStream);
    sampleDomainInputStream.close();

    // add attributes with default values from sample domain inputs to domain map
    sampleDomainMap.forEach(domainMap::putIfAbsent);

    domainUid = (String) domainMap.get("domainUID");
    // Customize the create domain job inputs
    domainNS = (String) domainMap.get("namespace");
    adminServerName = (String) domainMap.get("adminServerName");
    managedServerNameBase = (String) domainMap.get("managedServerNameBase");

    initialManagedServerReplicas =
        ((Integer) domainMap.get("initialManagedServerReplicas")).intValue();
    configuredManagedServerCount =
        ((Integer) domainMap.get("configuredManagedServerCount")).intValue();
    exposeAdminT3Channel = ((Boolean) domainMap.get("exposeAdminT3Channel")).booleanValue();
    exposeAdminNodePort = ((Boolean) domainMap.get("exposeAdminNodePort")).booleanValue();
    t3ChannelPort = ((Integer) domainMap.get("t3ChannelPort")).intValue();
    clusterName = (String) domainMap.get("clusterName");
    clusterType = (String) domainMap.get("clusterType");
    serverStartPolicy = (String) domainMap.get("serverStartPolicy");

    if (exposeAdminT3Channel) {
      domainMap.put("t3PublicAddress", TestUtils.getHostName());
    }

    String imageName = "phx.ocir.io/weblogick8s/oracle/fmw-infrastructure";
    if (System.getenv("IMAGE_NAME_FMWINFRA") != null) {
      imageName = System.getenv("IMAGE_NAME_FMWINFRA");
      logger.info("IMAGE_NAME_FMWINFRA " + imageName);
    }

    String imageTag = "12.2.1.3";
    if (System.getenv("IMAGE_TAG_FMWINFRA") != null) {
      imageTag = System.getenv("IMAGE_TAG_FMWINFRA");
      logger.info("IMAGE_TAG_FMWINFRA " + imageTag);
    }
    domainMap.put("logHome", "/shared/logs/" + domainUid);

    if (!domainMap.containsKey("domainHomeImageBase")) {
      domainMap.put("domainHome", "/shared/domains/" + domainUid);

      // update jrf/create-domain-script.sh with domain_name and rcuprefix
      TestUtils.replaceStringInFile(
          BaseTest.getResultDir() + "/jrf/create-domain-script.sh", "%RCUPREFIX%", domainUid);
      TestUtils.replaceStringInFile(
          BaseTest.getResultDir() + "/jrf/create-domain-script.sh", "%DOMAIN_NAME%", domainUid);
      domainMap.put("createDomainFilesDir", BaseTest.getResultDir() + "/jrf");
      domainMap.put("image", imageName + ":" + imageTag);
    }

    if (domainMap.containsKey("domainHomeImageBuildPath")) {
      domainMap.put(
          "domainHomeImageBuildPath",
          BaseTest.getResultDir() + "/" + domainMap.get("domainHomeImageBuildPath"));
    }
    if (System.getenv("IMAGE_PULL_SECRET_WEBLOGIC") != null) {
      domainMap.put("imagePullSecretName", System.getenv("IMAGE_PULL_SECRET_WEBLOGIC"));
      if (System.getenv("WERCKER") != null) {
        // create docker registry secrets
        TestUtils.createDockerRegistrySecret(
            System.getenv("IMAGE_PULL_SECRET_WEBLOGIC"),
            System.getenv("REPO_SERVER"),
            System.getenv("REPO_USERNAME"),
            System.getenv("REPO_PASSWORD"),
            System.getenv("REPO_EMAIL"),
            domainNS);
      }
    } else {
      domainMap.put("imagePullSecretName", "docker-store");
    }
    // remove null values if any attributes
    domainMap.values().removeIf(Objects::isNull);

    // create config map and secret for custom sit config
    if ((domainMap.get("configOverrides") != null)
        && (domainMap.get("configOverridesFile") != null)) {
      // write hostname in config file for public address

      String configOverridesFile = domainMap.get("configOverridesFile").toString();

      String cmd =
          "kubectl -n "
              + domainNS
              + " create cm "
              + domainUid
              + "-"
              + domainMap.get("configOverrides")
              + " --from-file "
              + BaseTest.getProjectRoot()
              + configOverridesFile;
      ExecResult result = ExecCommand.exec(cmd);
      if (result.exitValue() != 0) {
        throw new RuntimeException(
            "FAILURE: command " + cmd + " failed, returned " + result.stderr());
      }
      cmd =
          "kubectl -n "
              + domainNS
              + " label cm "
              + domainUid
              + "-"
              + domainMap.get("configOverrides")
              + " weblogic.domainUID="
              + domainUid;
      result = ExecCommand.exec(cmd);
      if (result.exitValue() != 0) {
        throw new RuntimeException(
            "FAILURE: command " + cmd + " failed, returned " + result.stderr());
      }
      // create secret for custom sit config t3 public address
      cmd =
          "kubectl -n "
              + domainNS
              + " create secret generic "
              + domainUid
              + "-"
              + "t3publicaddress "
              + " --from-literal=hostname="
              + TestUtils.getHostName();
      result = ExecCommand.exec(cmd);
      if (result.exitValue() != 0) {
        throw new RuntimeException(
            "FAILURE: command " + cmd + " failed, returned " + result.stderr());
      }
    }
  }
}
