/*******************************************************************************
 * Copyright (c) 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.gerritfs;

import javax.servlet.http.HttpServletRequest;

public interface IURIUnqualificationStrategy {

	public String getName();
	
	public void run(HttpServletRequest req, Object result);

}