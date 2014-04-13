package myPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.simpledb.*;
import com.amazonaws.services.simpledb.model.*;


public class ViewDB {
	static AmazonSimpleDB sdb;
	static String myDomain = "Proj1b";
	static String myItem = "Server";
	static String typeAttribute = "Type";
	static String serverType = "Server";
	static String sizeType = "Size";
	static String IPAttribute = "IP";
	static String sizeAttribute = "Size";
	public static void main(String[] args){
		
		
		try {
			sdb = new AmazonSimpleDBClient(new PropertiesCredentials(
					ViewDB.class.getResourceAsStream("AwsCredentials.properties")));
			System.out.println(sdb.listDomains());
			
			//If sdb doesn't exist somehow
			if(!sdb.listDomains().getDomainNames().contains(myDomain)){
				sdb.createDomain(new CreateDomainRequest(myDomain));
			}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		View v = new View();
		HashSet<String> arr = new HashSet<String>();
		View.insert(v, "1.1.1.1");
		View.insert(v, "1.1.1.2");
		View.insert(v, "1.1.1.3");
		writeSDBView(v);
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		readSDBView();
	}
	
	public static View readSDBView(){
		String selectExpression = "select * from " + myDomain + " where Type = '" + serverType + "'";
		SelectRequest req = new SelectRequest(selectExpression);
		
		View v = new View();
		
		//Get ip addresses stored in SimpleDB
		for(Item item : sdb.select(req).getItems()){
			for(Attribute attr : item.getAttributes()){
				if(attr.getName().equals(IPAttribute)){
					System.out.println(attr.getName());
					System.out.println(attr.getValue());
					View.insert(v, attr.getValue());
					break;
				}
			}
		}
		return v;
	}
	
	//Takes two parameters:
	//1: List of ip addresses to be written
	//2: number of attributes stored in database previously(must remove extras)
	public static void writeSDBView(View v){
		int size = 0;
		int i=0;
		HashSet<String> ips = View.getIPs(v);
		//Replace previous attributes with new IP addresses
		for(String ip : ips){
			ReplaceableAttribute replaceAttributeType = new ReplaceableAttribute().withName(typeAttribute).
					withValue(serverType).withReplace(true);
			ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(IPAttribute).
					withValue(ip).withReplace(true);
			
			sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem + i)
					.withAttributes(replaceAttributeType, replaceAttribute));
			i++;
		}
		
		//Get size of database so we can overwrite old entries
		String selectExpression = "select * from " + myDomain + " where Type = '" + sizeType + "'";
		SelectRequest req = new SelectRequest(selectExpression);
		ArrayList<String> arr = new ArrayList<String>();
		for(Item item : sdb.select(req).getItems()){
			for(Attribute attr : item.getAttributes()){
				if(attr.getName().equals(sizeAttribute)){
					arr.add(attr.getValue());
					size = Integer.parseInt(attr.getValue());
					break;
				}
			}
		}
		
		//Add size item
		ReplaceableAttribute replaceAttributeSize = new ReplaceableAttribute().withName(sizeAttribute).
				withValue(Integer.toString(ips.size())).withReplace(true);		
		ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(typeAttribute).
				withValue(sizeType).withReplace(true);
		
		sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem + i)
				.withAttributes(replaceAttribute,replaceAttributeSize));
		i++;//Delete all items after size item
		
		//Delete extra attributes
		for(; i < size; i++){
			sdb.deleteAttributes(new DeleteAttributesRequest(myDomain, myItem + i));
		}
	}
}
