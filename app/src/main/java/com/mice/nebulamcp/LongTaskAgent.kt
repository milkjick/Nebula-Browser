package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent resumable task/checkpoint store. Execution is delegated to ToolRegistry. */
class LongTaskAgent(context: Context, private val tools: ToolRegistry) {
    private val prefs = context.getSharedPreferences("nebula_long_tasks", Context.MODE_PRIVATE)
    data class Task(val id:String,val goal:String,val steps:JSONArray,val index:Int,val status:String,val result:String)

    @Synchronized fun create(goal:String, steps:JSONArray): Task {
        val t=JSONObject().put("id",UUID.randomUUID().toString()).put("goal",goal).put("steps",steps).put("index",0).put("status","queued").put("result","")
        save(t); return parse(t)
    }
    fun get(id:String): Task? = all().firstOrNull { it.id==id }
    fun list():List<Task> = all()
    @Synchronized fun cancel(id:String):Boolean { val o=raw(id) ?: return false; o.put("status","cancelled"); save(o); return true }

    /** Executes at most [maxSteps] per call, persisting a checkpoint after every successful step. */
    @Synchronized fun run(id:String,maxSteps:Int=5): Task? {
        val o=raw(id) ?: return null
        if(o.optString("status")=="cancelled"||o.optString("status")=="done") return parse(o)
        val steps=o.optJSONArray("steps") ?: JSONArray(); var idx=o.optInt("index",0); o.put("status","running")
        var count=0
        while(idx<steps.length() && count<maxSteps){
            val step=steps.optJSONObject(idx) ?: JSONObject(); val action=step.optString("action")
            val args=step.optJSONObject("arguments") ?: JSONObject()
            val result=tools.call(action,args)
            if(result.optBoolean("isError",false)){ o.put("status","failed").put("result",result.toString()); save(o); return parse(o) }
            idx++; count++; o.put("index",idx).put("result",result.toString()); save(o)
        }
        o.put("status",if(idx>=steps.length())"done" else "paused"); save(o); return parse(o)
    }
    private fun all():List<Task>{ val a=JSONArray(prefs.getString("tasks","[]")?:"[]"); return (0 until a.length()).mapNotNull { runCatching { parse(a.getJSONObject(it)) }.getOrNull() } }
    private fun raw(id:String):JSONObject?{ val a=JSONArray(prefs.getString("tasks","[]")?:"[]"); for(i in 0 until a.length()){val o=a.optJSONObject(i);if(o?.optString("id")==id)return o};return null }
    private fun save(o:JSONObject){
        val old=JSONArray(prefs.getString("tasks","[]")?:"[]"); val out=JSONArray(); var found=false
        for(i in 0 until old.length()) { val item=old.optJSONObject(i); if(item?.optString("id")==o.optString("id")){out.put(o);found=true}else if(item!=null)out.put(item) }
        if(!found)out.put(o); while(out.length()>50)out.remove(0); prefs.edit().putString("tasks",out.toString()).apply()
    }
    private fun parse(o:JSONObject)=Task(o.optString("id"),o.optString("goal"),o.optJSONArray("steps")?:JSONArray(),o.optInt("index"),o.optString("status"),o.optString("result"))
}
