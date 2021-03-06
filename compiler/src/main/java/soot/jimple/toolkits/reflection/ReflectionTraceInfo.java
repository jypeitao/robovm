/* Soot - a J*va Optimization Framework
 * Copyright (C) 2010 Eric Bodden
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.jimple.toolkits.reflection;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import soot.Body;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.tagkit.Host;
import soot.tagkit.SourceLnPosTag;

public class ReflectionTraceInfo {
	
	protected Map<SootMethod,Set<String>> classForNameReceivers;
	
	protected Map<SootMethod,Set<String>> classNewInstanceReceivers;

	protected Map<SootMethod,Set<String>> constructorNewInstanceReceivers;

	protected Map<SootMethod,Set<String>> methodInvokeReceivers;

	public ReflectionTraceInfo(String logFile) {
		classForNameReceivers = new HashMap<SootMethod, Set<String>>();
		classNewInstanceReceivers = new HashMap<SootMethod, Set<String>>();
		constructorNewInstanceReceivers = new HashMap<SootMethod, Set<String>>();
		methodInvokeReceivers = new HashMap<SootMethod, Set<String>>();

		if(logFile==null) {
			throw new InternalError("Trace based refection model enabled but no trace file given!?");
		} else {
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
				String line;
				int lines = 0;
				while((line=reader.readLine())!=null) {
					if(line.length()==0) continue;
					String[] portions = line.split(";",-1);
					String kind = portions[0];
					String target = portions[1];
					String source = portions[2];
					int lineNumber = portions[3].length()==0 ? -1 : Integer.parseInt(portions[3]);

					Set<SootMethod> possibleSourceMethods = inferSource(source, lineNumber);
					for (SootMethod sourceMethod : possibleSourceMethods) {
						if(kind.equals("Class.forName")) {
							Set<String> receiverNames;
							if((receiverNames=classForNameReceivers.get(sourceMethod))==null) {
								classForNameReceivers.put(sourceMethod, receiverNames = new HashSet<String>());
							}
							receiverNames.add(target);
						} else if(kind.equals("Class.newInstance")) {
							Set<String> receiverNames;
							if((receiverNames=classNewInstanceReceivers.get(sourceMethod))==null) {
								classNewInstanceReceivers.put(sourceMethod, receiverNames = new HashSet<String>());
							}
							receiverNames.add(target);
						} else if(kind.equals("Method.invoke")) {
							if(!Scene.v().containsMethod(target)) {
								throw new RuntimeException("Unknown method for signature: "+target);
							}
							
							Set<String> receiverNames;
							if((receiverNames=methodInvokeReceivers.get(sourceMethod))==null) {
								methodInvokeReceivers.put(sourceMethod, receiverNames = new HashSet<String>());
							}
							receiverNames.add(target);								
						} else if (kind.equals("Constructor.newInstance")) {
							if(!Scene.v().containsMethod(target)) {
								throw new RuntimeException("Unknown method for signature: "+target);
							}
							
							Set<String> receiverNames;
							if((receiverNames=constructorNewInstanceReceivers.get(sourceMethod))==null) {
								constructorNewInstanceReceivers.put(sourceMethod, receiverNames = new HashSet<String>());
							}
							receiverNames.add(target);								
						} else
							throw new RuntimeException("Unknown entry kind: "+kind);
					}
					lines++;
				}
			} catch (FileNotFoundException e) {
				throw new RuntimeException("Trace file not found.",e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private Set<SootMethod> inferSource(String source, int lineNumber) {
		String className = source.substring(0,source.lastIndexOf("."));
		String methodName = source.substring(source.lastIndexOf(".")+1);
		if(!Scene.v().containsClass(className)) {
			Scene.v().addBasicClass(className, SootClass.BODIES);
			Scene.v().loadBasicClasses();
			if(!Scene.v().containsClass(className)) {
				throw new RuntimeException("Trace file refers to unknown class: "+className);
			}
		}

		SootClass sootClass = Scene.v().getSootClass(className);
		Set<SootMethod> methodsWithRightName = new HashSet<SootMethod>();
		for (SootMethod m: sootClass.getMethods()) {
			if(m.getName().equals(methodName)) {
				methodsWithRightName.add(m);
			}
		} 

		if(methodsWithRightName.isEmpty()) {
			throw new RuntimeException("Trace file refers to unknown method with name "+methodName+" in Class "+className);
		} else if(methodsWithRightName.size()==1) {
			return Collections.singleton(methodsWithRightName.iterator().next());
		} else {
			//more than one method with that name
			for (SootMethod sootMethod : methodsWithRightName) {
				if(coversLineNumber(lineNumber, sootMethod)) {
					return Collections.singleton(sootMethod);
				}
				if(sootMethod.hasActiveBody()) {
					Body body = sootMethod.getActiveBody();
					if(coversLineNumber(lineNumber, body)) {
						return Collections.singleton(sootMethod);
					}
					for (Unit u : body.getUnits()) {
						if(coversLineNumber(lineNumber, u)) {
							return Collections.singleton(sootMethod);
						}
					}
				}
			}
			
			//if we get here then we found no method with the right line number information;
			//be conservative and return all method that we found
			return methodsWithRightName;				
		}
	}

	private boolean coversLineNumber(int lineNumber, Host host) {
		SourceLnPosTag tag = (SourceLnPosTag) host.getTag("SourceLnPosTag");
		if(tag!=null) {
			if(tag.startLn()<=lineNumber && tag.endLn()>=lineNumber) {
				return true;
			}
		}
		return false;
	}
	
	public Set<String> classForNameClassNames(SootMethod container) {
		if(!classForNameReceivers.containsKey(container)) return Collections.emptySet();
		return classForNameReceivers.get(container);
	}

	public Set<SootClass> classForNameClasses(SootMethod container) {
		Set<SootClass> result = new HashSet<SootClass>();
		for(String className: classForNameClassNames(container)) {
			result.add(Scene.v().getSootClass(className));
		}
		return result;
	}
	
	public Set<String> classNewInstanceClassNames(SootMethod container) {
		if(!classNewInstanceReceivers.containsKey(container)) return Collections.emptySet();
		return classNewInstanceReceivers.get(container);
	}
	
	public Set<SootClass> classNewInstanceClasses(SootMethod container) {
		Set<SootClass> result = new HashSet<SootClass>();
		for(String className: classNewInstanceClassNames(container)) {
			result.add(Scene.v().getSootClass(className));
		}
		return result;
	}
	
	public Set<String> constructorNewInstanceSignatures(SootMethod container) {
		if(!constructorNewInstanceReceivers.containsKey(container)) return Collections.emptySet();
		return constructorNewInstanceReceivers.get(container);
	}
	
	public Set<SootMethod> constructorNewInstanceConstructors(SootMethod container) {
		Set<SootMethod> result = new HashSet<SootMethod>();
		for(String signature: constructorNewInstanceSignatures(container)) {
			result.add(Scene.v().getMethod(signature));
		}
		return result;
	}
	
	public Set<String> methodInvokeSignatures(SootMethod container) {
		if(!methodInvokeReceivers.containsKey(container)) return Collections.emptySet();
		return methodInvokeReceivers.get(container);
	}

	public Set<SootMethod> methodInvokeMethods(SootMethod container) {
		Set<SootMethod> result = new HashSet<SootMethod>();
		for(String signature: methodInvokeSignatures(container)) {
			result.add(Scene.v().getMethod(signature));
		}
		return result;
	}
}