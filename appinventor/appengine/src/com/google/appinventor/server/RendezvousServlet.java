// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2016-2020 AppyBuilder.com, All Rights Reserved - Info@AppyBuilder.com
// https://www.gnu.org/licenses/gpl-3.0.en.html

// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2012 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

/*
 * RendezvousServlet -- This servlet acts as the rendezvous point
 *      between the blocks editor and a phone being used for debugging
 *      with a WiFi connection.  This was originally written in Python
 *      using the "Bottle" micro-framework. Here is that code:
 *
 * #!/usr/bin/python
 * from bottle import run,route,app,request,response,template,default_app,Bottle,debug,abort
 * from flup.server.fcgi import WSGIServer
 * from cStringIO import StringIO
 * import memcache
 *
 * app = Bottle()
 * default_app.push(app)
 *
 * @route('/', method='POST')
 * def store():
 *     c = memcache.Client(['127.0.0.1:11211',])
 *     key = request.POST.get('key')
 *     if not key:
 *         abort(404, 'No Key Specified')
 *     d = {}
 *     for k,v in request.POST.items():
 *         d[k] = v
 *     c.set('rr-%s' % key, d, 1800)
 *     return d
 *
 * @route('/<key>')
 * def fetch(key):
 *     c = memcache.Client(['127.0.0.1:11211',])
 *     return c.get('rr-%s' % key)
 *
 * debug(True)
 *
 * ##run(host='127.0.0.1', port=8080)
 * WSGIServer(app).run()
 *
 * # End of Python Code
 *
 *      This code is a little bit more complicated. In part because it
 *      is written in Java and it is intended to be run within the
 *      Google App Engine.  The App Engine can sometimes disable
 *      memcache, which this code uses both for speed and to avoid
 *      using the datastore for data which is valuable for typically
 *      10 to 15 seconds!
 *
 *      When memcache is disabled we use the datastore. However when
 *      memcache is available we do *NOT* use the datastore at
 *      all. This is a little different from the way most code uses
 *      memcache, literally as a cache in front of a real data
 *      store. Again, this is driven by the desire for speed and the
 *      short life of the data itself.
 *
 *      Note: At the moment there is no code to cleaup entries left in
 *      the datastore. However each entry is marked with a used date,
 *      so it is pretty easy to write code at a later date to remove
 *      stale entries. Where stale can be defined to be data that is
 *      more then a few minutes old!
 *
 */

package com.google.appinventor.server;

import com.google.appengine.api.capabilities.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletException;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.HashMap;

import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.memcache.Expiration;

import org.json.JSONObject;
import org.json.JSONException;

import com.google.appinventor.server.storage.StorageIo;
import com.google.appinventor.server.storage.StorageIoInstanceHolder;

@SuppressWarnings("unchecked")
public class RendezvousServlet extends HttpServlet {

  private final MemcacheService memcache = MemcacheServiceFactory.getMemcacheService();

  private final String rendezvousuuid = "c96d8ac6-e571-48bb-9e1f-58df18574e43"; // UUID Generated by JIS

  private final StorageIo storageIo = StorageIoInstanceHolder.INSTANCE;

  public void init(ServletConfig config) throws ServletException {
    super.init(config);
  }

  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String uriComponents[] = req.getRequestURI().split("/", 5);
    String key = uriComponents[uriComponents.length-1];
    resp.setContentType("text/plain");
    PrintWriter out = resp.getWriter();
    JSONObject jsonObject = new JSONObject();

    if (memcacheNotAvailable()) {
      // Don't have memcache at the moment, use the data store.
      String ipAddress = storageIo.findIpAddressByKey(key);
      if (ipAddress == null) {
//        out.println("");
      } else {
        try {
          jsonObject.put("key", key);
          jsonObject.put("ipaddr", ipAddress);
        } catch (JSONException e) {
          e.printStackTrace();
        }
        out.println(jsonObject.toString());
      }
      return;
    }

    Object value = memcache.get(rendezvousuuid + key);
    if (value == null) {
//      out.println("");
      return;
    }

    if (value instanceof Map) {
      Map map = (Map<String, String>) value;
      for (Object mkey : map.keySet()) {
        try {
          jsonObject.put((String) mkey, map.get(mkey));
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
      out.println(jsonObject.toString());
    } else
      out.println("");
  }

  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    PrintWriter out = resp.getWriter();
    BufferedReader input = new BufferedReader(new InputStreamReader(req.getInputStream()));
    String queryString = input.readLine();

    if (queryString == null) {
      out.println("queryString is null");
      return;
    }

    HashMap<String, String> params = getQueryMap(queryString);
    String key = params.get("key");
    if (key == null) {
      out.println("no key");
      return;
    }

    if (memcacheNotAvailable()) {
      String ipAddress = params.get("ipaddr");
      if (ipAddress == null) {  // Malformed request
        out.println("no ipaddress");
        return;
      }
      storageIo.storeIpAddressByKey(key, ipAddress);
      out.println("OK (Datastore)");
      return;
    }

    memcache.put(rendezvousuuid + key, params, Expiration.byDeltaSeconds(300));
    out.println("OK");
  }

  public void destroy() {
    super.destroy();
  }

  private static HashMap<String, String> getQueryMap(String query)  {
    String[] params = query.split("&");
    HashMap<String, String> map = new HashMap<String, String>();
    for (String param : params)  {
      String name = param.split("=")[0];
      String value = param.split("=")[1];
      map.put(name, value);
    }
    return map;
  }

  private boolean memcacheNotAvailable() {
    CapabilitiesService service = CapabilitiesServiceFactory.getCapabilitiesService();
    CapabilityStatus status = service.getStatus(Capability.MEMCACHE).getStatus();
    if (status == CapabilityStatus.DISABLED) {
      return true;
    } else {
      return false;
    }
  }

}