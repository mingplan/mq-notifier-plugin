package com.sonymobile.jenkins.plugins.mq.mqnotifier.providers;


import com.cloudbees.workflow.rest.external.ErrorExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import hudson.Extension;
import hudson.model.Run;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 *  Provides information about pipeline when this build is pipeline type
 *  @author hmzhu@freewheel.tv
 */
@Extension(optional = true)
public class PipelineProvider extends MQDataProvider {

    public static final String Key_Pipeline = "pipelines";

    @Override
    public void provideCompletedRunData(Run run, JSONObject json) {
        List<List<String >> pipelines = new LinkedList<List<String>>();
        Boolean is_pipeline = false;
        if (run instanceof WorkflowRun) {
            is_pipeline = true;
            WorkflowRun cur_run =  (WorkflowRun) run;
            RunExt runs = RunExt.create(cur_run).createWrapper();
            System.out.print("[Output] pipeline data");
            System.out.print(runs.getName());
            for (StageNodeExt stage:runs.getStages()) {
                List<String> item = new ArrayList<String>();
                StageNodeExt cur_stage = stage.myWrapper();
                System.out.println(cur_stage.getExecNode());
                System.out.println();
                String node = "node" + "=" + cur_stage.getExecNode().toString();
                item.add(node);
                System.out.println(cur_stage.getAllChildNodeIds());
                System.out.println();
                System.out.println(cur_stage.getName());
                String name = "name" + "=" + cur_stage.getName();
                item.add(name);
                System.out.println("start time");
                System.out.println(cur_stage.getStartTimeMillis());
                String start_time = "start_time" + "=" + cur_stage.getStartTimeMillis();
                item.add(start_time);
                System.out.println("duration (ms)");
                System.out.println(cur_stage.getDurationMillis());
                String duration = "duration" + "=" + cur_stage.getDurationMillis();
                item.add(duration);
                System.out.println();
                System.out.println(cur_stage.getPauseDurationMillis());
                String pause = "pause" + "=" + cur_stage.getPauseDurationMillis();
                item.add(pause);
                ErrorExt error = cur_stage.getError();
                if (error != null) {
                    System.out.println("Error message");
                    System.out.println(cur_stage.getError().getMessage());
                    String error_msg = "error" + "=" + cur_stage.getError().getMessage();
                    item.add(error_msg);
                    System.out.println(cur_stage.getError().getType());
                }
                System.out.println("status");
                System.out.println(cur_stage.getStatus().name());
                String status = "status" + "=" + cur_stage.getStatus().name();
                item.add(status);
                pipelines.add(item);

            }
        }
        json.put("is_pipeline", is_pipeline);
        json.put(Key_Pipeline, pipelines);

    }


}

