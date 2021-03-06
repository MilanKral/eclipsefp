/**
 * (c) 2011, Alejandro Serrano
 * Released under the terms of the EPL.
 */
package net.sf.eclipsefp.haskell.browser.items;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents the information from a module returned
 * by a Hoogle search.
 * 
 * @author Alejandro Serrano
 */
public class HoogleResultModule extends HoogleResult {
	ArrayList<PackageIdentifier> pkg;
	Module mod;

	public HoogleResultModule(PackageIdentifier pkg, Module mod) {
		setType(HoogleResultType.MODULE);
		this.pkg = new ArrayList<>();
		this.pkg.add(pkg);
		this.mod = mod;
	}

	public HoogleResultModule(JSONObject o) throws JSONException {
		setType(HoogleResultType.MODULE);
		JSONArray results = o.getJSONArray("results");
		// Get info from first result
		JSONArray first_result = results.getJSONArray(0);
		this.mod = new Module(first_result.getJSONObject(1));
		// Add packages
		this.pkg = new ArrayList<>();
		for (int i = 0; i < results.length(); i++) {
			JSONArray result = results.getJSONArray(i);
			this.pkg.add(new PackageIdentifier(result.getJSONObject(0)));
		}
	}

	public ArrayList<PackageIdentifier> getPackageIdentifiers() {
		return this.pkg;
	}
	
	public Module getModule() {
		return this.mod;
	}

	@Override
	public String getName() {
		return this.mod.getName();
	}
	
	@Override
	public String getCompleteDefinition() {
		return "module " + this.getName();
	}
}
