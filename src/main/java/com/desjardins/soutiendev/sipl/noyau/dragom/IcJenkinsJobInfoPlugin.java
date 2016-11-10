package com.desjardins.soutiendev.sipl.noyau.dragom;

import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactInfoPlugin;
import org.azyva.dragom.model.plugin.JenkinsJobInfoPlugin;
import org.azyva.dragom.model.plugin.impl.SimpleJenkinsJobInfoPluginBaseImpl;
import org.azyva.dragom.reference.ReferenceGraph;

public class IcJenkinsJobInfoPlugin extends SimpleJenkinsJobInfoPluginBaseImpl implements JenkinsJobInfoPlugin {

	public IcJenkinsJobInfoPlugin(Module module) {
		super(module);
		// TODO Auto-generated constructor stub
	}

	@Override
	public String getTemplate() {
		return "assemblage/ic/modele-job-assemblage-ic-maven-1-fs";
	}

	@Override
	public Map<String, String> getMapTemplateParam(ReferenceGraph referenceGraph, Version version) {
		Model model;
		Map<String, String> mapTemplateParam;
		ArtifactInfoPlugin artifactInfoPlugin;
		ArtifactGroupId artifactGroupId;
		List<ReferenceGraph.Referrer> listReferrer;
		StringBuilder stringBuilder;

		model = ExecContextHolder.get().getModel();
		mapTemplateParam = new HashMap<String, String>();

		artifactInfoPlugin = this.getModule().getNodePlugin(ArtifactInfoPlugin.class, null);
		artifactGroupId = artifactInfoPlugin.getSetDefiniteArtifactGroupIdProduced().iterator().next();

		mapTemplateParam.put("URL_DEPOT_GIT", "");
		mapTemplateParam.put("BRANCHE", version.getVersion());
		mapTemplateParam.put("GROUP_ID", artifactGroupId.getGroupId());
		mapTemplateParam.put("ARTIFACT_ID", artifactGroupId.getArtifactId());
		mapTemplateParam.put("JDK", "JFK 1.8.0");
		mapTemplateParam.put("MAVEN", "Maven 3.2");

		listReferrer = referenceGraph.getListReferrer(new ModuleVersion(this.getModule().getNodePath(), version));
		stringBuilder = new StringBuilder();

		for (ReferenceGraph.Referrer referrer: listReferrer) {
			Module module;
			JenkinsJobInfoPlugin jenkinsJobInfoPlugin;

			module = model.getModule(referrer.getModuleVersion().getNodePath());
			jenkinsJobInfoPlugin = module.getNodePlugin(JenkinsJobInfoPlugin.class, null);

			if (stringBuilder.length() != 0) {
				stringBuilder.append(',');
			}

			stringBuilder.append(jenkinsJobInfoPlugin.getJobFullName(referrer.getModuleVersion().getVersion()));
		}

		mapTemplateParam.put("JOBS_AVAL", stringBuilder.toString());

		return mapTemplateParam;
	}

	@Override
	public Reader getReaderConfig(ReferenceGraph referenceGraph, Version version) {
		return null;
	}
}

/*
Take information from dragom.properties file in module (jdk, maven version, etc.)
*/