package myPackage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.simpledb.*;
import com.amazonaws.services.simpledb.model.*;


public class ViewDB {
	static AmazonSimpleDB sdb;
	static String myDomain = "Proj1bJimmy";
	static String myItem = "Server";

	static String typeAttribute = "Type";
	static String serverType = "Server";
	static String sizeType = "Size";

	static String IPAttribute = "IP";
	static String sizeAttribute = "Size";
	static String indexAttribute = "Index";
	static String validAttribute = "Valid";

	static String trueValue = "True";
	static String falseValue = "False";

	public static void init(){
		try {
			sdb = new AmazonSimpleDBClient(new PropertiesCredentials(
					ViewDB.class.getResourceAsStream("AwsCredentials.properties")));

			//If sdb doesn't exist somehow
			if(!sdb.listDomains().getDomainNames().contains(myDomain)){
				sdb.createDomain(new CreateDomainRequest(myDomain));
			}

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static View readSDBView(int ViewSz){
		String selectExpression = "select * from " + myDomain + " where Type = '" + serverType + "'" +
				" and " + validAttribute + " = '" + trueValue + "'" +
				" and " + indexAttribute + " < '" + ViewSz + "'";
		SelectRequest req = new SelectRequest(selectExpression);

		View v = new View();
		SelectResult sr = sdb.select(req);
		if(sr == null)return v;
		//Get ip addresses stored in SimpleDB
		for(Item item : sr.getItems()){
			for(Attribute attr : item.getAttributes()){
				if(attr.getName().equals(IPAttribute)){	
					View.insert(v, attr.getValue());
				}
			}
		}
		return v;
	}

	//Takes two parameters:
	//1: List of ip addresses to be written
	//2: number of attributes stored in database previously(must remove extras)
	public static void writeSDBView(View v, int ViewSz){
		int i=0;
		HashSet<String> ips = View.getIPs(v);
		
		//Replace previous attributes with new IP addresses
		for(String ip : ips){
			try {
				InetAddress s = InetAddress.getByName(ip);
				if(!s.isLoopbackAddress()){
					String readableIP = ip;
					ReplaceableAttribute replaceAttributeType = new ReplaceableAttribute().withName(typeAttribute).
							withValue(serverType).withReplace(true);
					ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(IPAttribute).
							withValue(readableIP).withReplace(true);
					ReplaceableAttribute replaceAttributeIndex = new ReplaceableAttribute().withName(indexAttribute).
							withValue(Integer.toString(i)).withReplace(true);

					String validValue = (readableIP.equals("0.0.0.0")) ? falseValue : trueValue;
					ReplaceableAttribute replaceAttributeValid = new ReplaceableAttribute().withName(validAttribute).
							withValue(validValue).withReplace(true);

					sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem + i)
							.withAttributes(replaceAttributeType, replaceAttribute, replaceAttributeIndex, replaceAttributeValid));
					i++;
				}
			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
			}
		}

		//Extra entries filled in with "0.0.0.0" and marked invalid
		for(; i < ViewSz; i++){
			ReplaceableAttribute replaceAttributeType = new ReplaceableAttribute().withName(typeAttribute).
					withValue(serverType).withReplace(true);
			ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(IPAttribute).
					withValue("0.0.0.0").withReplace(true);
			ReplaceableAttribute replaceAttributeIndex = new ReplaceableAttribute().withName(indexAttribute).
					withValue(Integer.toString(i)).withReplace(true);
			ReplaceableAttribute replaceAttributeValid = new ReplaceableAttribute().withName(validAttribute).
					withValue(falseValue).withReplace(true);

			sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem + i)
					.withAttributes(replaceAttributeType, replaceAttribute, replaceAttributeIndex, replaceAttributeValid));
		}

		ReplaceableAttribute replaceAttributeSize = new ReplaceableAttribute().withName(sizeAttribute).
				withValue(Integer.toString(ips.size())).withReplace(true);		
		ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(typeAttribute).
				withValue(sizeType).withReplace(true);

		sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem + i)
				.withAttributes(replaceAttribute,replaceAttributeSize));
	}
}
