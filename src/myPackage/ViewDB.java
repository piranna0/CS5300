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
	static String myDomain = "Proj1b";
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

	public static void main(String[] args){
		//		
		//		
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
		//		
		//		
		////		View v = new View();
		////		HashSet<String> arr = new HashSet<String>();
		////		View.insert(v, "1.1.1.1");
		////		View.insert(v, "1.1.1.2");
		////		writeSDBView(v);
		////		try {
		////			Thread.sleep(3000);
		////		} catch (InterruptedException e) {
		////			// TODO Auto-generated catch block
		////			e.printStackTrace();
		////		}
		readSDBView(5);
		//		try {
		//			System.out.println(inetaddrToString(InetAddress.getLocalHost()));
		//			System.out.println(convertToReadableIP(inetaddrToString(InetAddress.getLocalHost())));
		//			System.out.println(InetAddress.getByAddress(inetaddrToString(InetAddress.getLocalHost()).getBytes()).getHostAddress());
		//		} catch (UnknownHostException e) {
		//			// TODO Auto-generated catch block
		//			e.printStackTrace();
		//		}
	}

	private static String inetaddrToString(InetAddress addr) {
		return new String(addr.getAddress());
	}

	public static void init(){
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
					try {
						View.insert(v, inetaddrToString(InetAddress.getByName(attr.getValue())));
						System.out.println(InetAddress.getByAddress(View.choose(v).getBytes()));
					} catch (UnknownHostException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					//					System.out.println("attr:" + attr.getValue());
					break;
				}
			}
		}
		return v;
	}

	//For testing readSDBView only
	public static String convertToReadableIP(String addr) {
		byte[] bytes = addr.getBytes();
		InetAddress a;
		try {
			a = InetAddress.getByAddress(bytes);
			return a.getHostAddress();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	/*
	 * TODO: Change Write so that it always writes ViewSz elements and then size. The deleting part scares me a little with concurrent accesses
	 */


	//Takes two parameters:
	//1: List of ip addresses to be written
	//2: number of attributes stored in database previously(must remove extras)
	public static void writeSDBView(View v, int ViewSz){
		int size = 0;
		int i=0;
		HashSet<String> ips = View.getIPs(v);
		//Replace previous attributes with new IP addresses
		for(String ip : ips){
			String readableIP = convertToReadableIP(ip);
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


		//Get size of database so we can overwrite old entries
		//		String selectExpression = "select * from " + myDomain + " where Type = '" + sizeType + "'";
		//		SelectRequest req = new SelectRequest(selectExpression);
		//		ArrayList<String> arr = new ArrayList<String>();
		//		for(Item item : sdb.select(req).getItems()){
		//			for(Attribute attr : item.getAttributes()){
		//				if(attr.getName().equals(sizeAttribute)){
		//					arr.add(attr.getValue());
		//					size = Integer.parseInt(attr.getValue());
		//					break;
		//				}
		//			}
		//		}
		//		
		//		//Add size item
		ReplaceableAttribute replaceAttributeSize = new ReplaceableAttribute().withName(sizeAttribute).
				withValue(Integer.toString(ips.size())).withReplace(true);		
		ReplaceableAttribute replaceAttribute = new ReplaceableAttribute().withName(typeAttribute).
				withValue(sizeType).withReplace(true);

		sdb.putAttributes(new PutAttributesRequest().withDomainName(myDomain).withItemName(myItem + i)
				.withAttributes(replaceAttribute,replaceAttributeSize));
		//		i++;//Delete all items after size item
		//		
		//		//Delete extra attributes
		//		for(; i < size+1; i++){
		//			sdb.deleteAttributes(new DeleteAttributesRequest(myDomain, myItem + i));
		//		}
	}
}
