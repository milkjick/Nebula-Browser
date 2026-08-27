package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Lightweight persistent browser workflow engine. Workflows are JSON and reuse ToolRegistry actions. */
class WorkflowEngine(context: Context, private val tools: ToolRegistry) {
    private val prefs=context.getSharedPreferences("nebula_workflows",Context.MODE_PRIVATE)
    data class Workflow(val id:String,val name:String,val steps:JSONArray)
    @Synchronized fun save(name:String,steps:JSONArray,id:String=UUID.randomUUID().toString()):Workflow{val o=JSONObject().put("id",id).put("name",name).put("steps",steps);val a=JSONArray(prefs.getString("items","[]")?:"[]");var replaced=false;for(i in 0 until a.length()){if(a.optJSONObject(i)?.optString("id")==id){a.put(i,o);replaced=true;break}};if(!replaced)a.put(o);prefs.edit().putString("items",a.toString()).apply();return Workflow(id,name,steps)}
    fun list():List<Workflow>{val a=JSONArray(prefs.getString("items","[]")?:"[]");return(0 until a.length()).mapNotNull{a.optJSONObject(it)?.let{j->Workflow(j.optString("id"),j.optString("name"),j.optJSONArray("steps")?:JSONArray())}}}
    fun run(id:String):JSONArray{val w=list().firstOrNull{it.id==id}?:return JSONArray();val out=JSONArray();for(i in 0 until w.steps.length()){val s=w.steps.optJSONObject(i)?:continue;val r=tools.call(s.optString("tool",s.optString("action")),s.optJSONObject("arguments")?:JSONObject());out.put(JSONObject().put("step",i+1).put("result",r));if(r.optBoolean("isError"))break};return out}
    @Synchronized fun delete(id:String){val a=JSONArray();list().filterNot{it.id==id}.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("steps",it.steps))};prefs.edit().putString("items",a.toString()).apply()}
}
