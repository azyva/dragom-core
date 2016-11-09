package com.desjardins.soutiendev.sipl.noyau.dragom;

import java.io.Reader;
import java.util.Map;

import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<String, String> getMapTemplateParam(
			ReferenceGraph referenceGraph, Version version) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Reader getReaderConfig(ReferenceGraph referenceGraph, Version version) {
		// TODO Auto-generated method stub
		return null;
	}
}


/*
Take information from dragom.properties file in module (maven version, etc.)
Need to have site-specific fonctionality
- GroupId, artifactId is too specific to Desjardins.
Maybe have some kind of simple plugin that simply builds the config.xml file.
*/