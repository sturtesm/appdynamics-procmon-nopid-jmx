/**
 * Copyright 2013 AppDynamics
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.appdynamics.extensions.process.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.log4j.Logger;

import com.appdynamics.extensions.process.config.Configuration;
import com.appdynamics.extensions.process.processdata.ProcessData;
import com.appdynamics.extensions.process.processexception.ProcessMonitorException;

public class WindowsParser extends Parser {

	private static final Logger logger = Logger.getLogger("com.singularity.extensions.WindowsParser");
	private int posName = -1, posPID = -1, posMem = -1;
	private int reportIntervalSecs, fetchesPerInterval;

	// for keeping track of the CPU load
	private Map<String, Long> oldDeltaCPUTime;
	private Map<String, Long> newDeltaCPUTime;

	public WindowsParser(Configuration config, int reportIntervalSecs, int fetchesPerInterval) {
		super(config);
		processGroupName = "Windows Processes";
		processes = new HashMap<String, ProcessData>();
		includeProcesses = new HashSet<String>();
		this.reportIntervalSecs = reportIntervalSecs;
		this.fetchesPerInterval = fetchesPerInterval;
		oldDeltaCPUTime = new HashMap<String, Long>();
		newDeltaCPUTime = new HashMap<String, Long>();
	}

	@Override
	public void retrieveMemoryMetrics() throws ProcessMonitorException {
		Runtime rt = Runtime.getRuntime();
		Process p = null;
		String cmd = "wmic OS get TotalVisibleMemorySize";
		BufferedReader input = null;
		try {
			p = rt.exec(cmd);
			input = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line;
			// skipping first lines
			input.readLine();
			input.readLine();
			// setting the total RAM size
			line = input.readLine();
			setTotalMemSizeMB(Integer.parseInt(line.trim()) / 1024);
		} catch (IOException e) {
			logger.error("Error in executing the command " + cmd, e);
			throw new ProcessMonitorException("Error in executing the command " + cmd, e);
		} catch (NumberFormatException e) {
			logger.error("Unable to retrieve total physical memory size (not a number) ", e);
			throw new ProcessMonitorException("Unable to retrieve total physical memory size (not a number) ", e);
		} finally {
			closeBufferedReader(input);
			cleanUpProcess(p, cmd);
		}
	}

	/**
	 * Parsing the 'tasklist' command and storing process level data
	 * 
	 * @throws ProcessMonitorException
	 */
	@Override
	public void parseProcesses() throws ProcessMonitorException {
		Runtime rt = Runtime.getRuntime();
		Process p = null;
		String cmd = "tasklist /fo csv";
		BufferedReader input = null;
		try {
			p = rt.exec(cmd);
			input = new BufferedReader(new InputStreamReader(p.getInputStream()));
			processHeader(input.readLine());
			String line;
			while ((line = input.readLine()) != null) {
				String[] words = line.split("\",\"");
				words[0] = words[0].replaceAll("\"", "");
				words[words.length - 1] = words[words.length - 1].replaceAll("\"", "");
	
				// retrieve single process information
				int pid = Integer.parseInt(words[posPID]);
				String procName = words[posName];
	
				float memPercent = (Float.parseFloat(words[posMem].replaceAll("\\D*", "")) / 1024) / getTotalMemSizeMB() * 100;
	
				if (procName != null) {
					// check if user wants to exclude this process
					if (!config.getExcludeProcesses().contains(procName) && !config.getExcludePIDs().contains(pid)) {
						// update the processes Map
						StringBuilder sb = new StringBuilder(procName);
				//TODO		procName = sb.append("|PID|").append(pid).toString();
						procName = sb.toString();
						if (processes.containsKey(procName)) {
							ProcessData procData = processes.get(procName);
							//procData.numOfInstances++;
							procData.memPercent += memPercent;
						} else {
							processes.put(procName, new ProcessData(procName, 0, memPercent));
						}
					}
	
				}
			}
			calcCPUTime();
		} catch (IOException e) {
			logger.error("Error in executing the command " + cmd, e);
			throw new ProcessMonitorException("Error in executing the command " + cmd, e);
		} catch (Exception e) {
			logger.error("Exception: " + e);
			throw new ProcessMonitorException("Exception: " + e);
		} finally {
			closeBufferedReader(input);
			cleanUpProcess(p, cmd);
		}
	}

	private void processHeader(String processLine) throws ProcessMonitorException {
		String[] words = processLine.replaceAll("\"", "").trim().split(",");
		for (int i = 0; i < words.length; i++) {
			if (words[i].equals("Image Name")) {
				posName = i;
			} else if (words[i].equals("PID")) {
				posPID = i;
			} else if (words[i].equals("Mem Usage")) {
				posMem = i;
			}
		}
		if (posName == -1 || posPID == -1 || posMem == -1) {
			throw new ProcessMonitorException("Could not find correct header information of 'tasklist -fo csv'. Terminating Process Monitor");
		}
	}

	/**
	 * calculates the cpu utilization in % for each process and updates the
	 * 'processes' hashmap
	 * 
	 * @throws ProcessMonitorException
	 */
	private void calcCPUTime() throws ProcessMonitorException {
		Runtime rt = Runtime.getRuntime();
		Process p = null;
		
		String cmd = getCommand();
		BufferedReader input = null;
		try {
			p = rt.exec(cmd);
			input = new BufferedReader(new InputStreamReader(p.getInputStream()));
			if(input.readLine() == null) {
				closeBufferedReader(input);
				input = new BufferedReader(new InputStreamReader(p.getErrorStream()));
				String errorString = input.readLine();
				if(errorString.toLowerCase().contains("invalid xsl format")){
					StringBuilder msg = new StringBuilder(errorString).append(" ");
					
					if (Configuration.DEFAULT_CSV_FILE_PATH.equals(config.getCsvFilePath())) {
						msg.append("csv.xls not found in C:\\Windows\\System32 or C:\\Windows\\SysWOW64 respectively.");
					} else {
						msg.append(config.getCsvFilePath()).append(" not found.");
					}
					
					msg.append(" Cannot process information for CPU usage (value 0 will be reported).");
					logger.warn(msg.toString());
					return;
				}
			}
			
			String cpudata;
			int cpuPosName = -1, cpuPosUserModeTime = -1, cpuPosKernelModeTime = -1, cpuPosProcessId = -1;
			String header = input.readLine();
			
			// sometimes the first line is empty, so need to cater for this
			while (header != null && header.trim().equals("")) {
				if (logger.isDebugEnabled()) {
					logger.debug("header is empty, checking the next line...");
				}
				
				header = input.readLine();
			}
			
			if (header != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("header found!");
				}
				
				String[] words = header.trim().split(",");
				for (int i = 0; i < words.length; i++) {
					if (words[i].toLowerCase().equals("name")) {
						cpuPosName = i;
					} else if (words[i].toLowerCase().equals("usermodetime")) {
						cpuPosUserModeTime = i;
					} else if (words[i].toLowerCase().equals("kernelmodetime")) {
						cpuPosKernelModeTime = i;
					} else if (words[i].toLowerCase().equals("processid")) {
						cpuPosProcessId = i;
					}
				}
			}

			if (cpuPosName == -1 || cpuPosUserModeTime == -1 || cpuPosKernelModeTime == -1 || cpuPosProcessId == -1) {
				input.close();
				throw new ProcessMonitorException(
						String.format("Could not find correct header information of '%s'. Terminating Process Monitor",
								cmd));
			}

			while ((cpudata = input.readLine()) != null) {
				String[] words = cpudata.trim().split(",");
				if (words.length < 5) {
					continue;
				}

				// retrieve single process information
				String procName = words[cpuPosName];
				// divide by 10000 to convert to milliseconds
				long userModeTime = Long.parseLong(words[cpuPosUserModeTime]) / 10000;
				long kernelModeTime = Long.parseLong(words[cpuPosKernelModeTime]) / 10000;
				int pid = Integer.parseInt(words[cpuPosProcessId]);
				StringBuilder sb = new StringBuilder(procName);
	//TODO	procName = sb.append("|PID|").append(pid).toString();
				procName = sb.toString();

				// update hashmaps used for CPU load calculations
				if (processes.containsKey(procName)) {
					if (newDeltaCPUTime.containsKey(procName)) {
						newDeltaCPUTime.put(procName, newDeltaCPUTime.get(procName) + userModeTime + kernelModeTime);
					} else {
						newDeltaCPUTime.put(procName, userModeTime + kernelModeTime);
					}
				}
			}
			// update CPU data in processes hash-map
			for (String key : newDeltaCPUTime.keySet()) {
				if (oldDeltaCPUTime.containsKey(key)) {
					// calculations involving the period and interval
					float delta = newDeltaCPUTime.get(key) - oldDeltaCPUTime.get(key);
					float time = reportIntervalSecs / fetchesPerInterval * 1000;
					ProcessData procData = processes.get(key);
					if (procData != null) {
						procData.CPUPercent += delta / time * 100;
					}
				}
			}
			oldDeltaCPUTime = newDeltaCPUTime;
			newDeltaCPUTime = new HashMap<String, Long>();
		} catch (IOException e) {
			logger.error("Error in executing the command " + cmd, e);
			throw new ProcessMonitorException("Error in executing the command " + cmd, e);
		} catch (Exception e) {
			logger.error("Exception: ", e);
			throw new ProcessMonitorException("Exception: ", e);
		} finally {
			closeBufferedReader(input);
			cleanUpProcess(p, cmd);
		}
	}
	
	private String getCommand() {
		StringBuilder cmdBuilder = new StringBuilder("wmic process get name,processid,usermodetime,kernelmodetime /format:");
		
		if (Configuration.DEFAULT_CSV_FILE_PATH.equals(config.getCsvFilePath())) {
			cmdBuilder.append(config.getCsvFilePath());
		} else {
			cmdBuilder.append("\"").append(config.getCsvFilePath()).append("\"");
		}
		
		return cmdBuilder.toString();
	}
}
