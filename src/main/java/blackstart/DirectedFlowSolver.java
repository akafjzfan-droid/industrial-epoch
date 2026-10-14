package blackstart;

import java.util.Arrays;

/** Conservative directed, lossy transport. Every positive flow belongs to a source-to-load path.
 * A transit graph must first have its own deficit covered; imports are never a new source.
 * Arrays/routes are reused and the work is bounded. This is dispatch, not AC voltage/power flow. */
final class DirectedFlowSolver {
    private static final double EPS=1e-8;
    private int nodes,edges,sourceCount,sinkCount,pathLength;
    private double pathFactor;
    private double[] supply=new double[0],demand=new double[0],available=new double[0],unmet=new double[0],sourceAsk=new double[0],request=new double[0];
    private double[] capacity=new double[0],remaining=new double[0],efficiency=new double[0],flow=new double[0],edgeAsk=new double[0],pathEfficiency=new double[0];
    private int[] from=new int[0],to=new int[0],priority=new int[0],headOut=new int[0],headIn=new int[0],nextOut=new int[0],nextIn=new int[0];
    private int[] distance=new int[0],nextEdge=new int[0],queue=new int[0];
    private int[] sources=new int[0],sinks=new int[0],path=new int[0];
    private double[] usedSource=new double[0],usedDemand=new double[0],usedEdge=new double[0];
    void begin(int n,int m){
        nodes=n;edges=m;
        if(supply.length<n){int c=Math.max(n,supply.length*2+8);supply=new double[c];demand=new double[c];available=new double[c];unmet=new double[c];sourceAsk=new double[c];request=new double[c];headOut=new int[c];headIn=new int[c];distance=new int[c];nextEdge=new int[c];queue=new int[c];pathEfficiency=new double[c];sources=new int[c];sinks=new int[c];path=new int[c];usedSource=new double[c];usedDemand=new double[c];}
        if(capacity.length<m){int c=Math.max(m,capacity.length*2+8);capacity=new double[c];remaining=new double[c];efficiency=new double[c];flow=new double[c];edgeAsk=new double[c];from=new int[c];to=new int[c];priority=new int[c];nextOut=new int[c];nextIn=new int[c];usedEdge=new double[c];}
        Arrays.fill(supply,0,n,0);Arrays.fill(demand,0,n,0);Arrays.fill(capacity,0,m,0);Arrays.fill(flow,0,m,0);
    }
    void node(int id,double export,double deficit){supply[id]=finite(export);demand[id]=finite(deficit);}
    void edge(int id,int source,int target,double cap,double lossFactor,int tier){
        from[id]=source;to[id]=target;capacity[id]=finite(cap);efficiency[id]=Double.isFinite(lossFactor)?Math.max(0,Math.min(1,lossFactor)):0;
        priority[id]=Math.max(1,Math.min(3,tier));
    }
    void capacity(int id,double cap){capacity[id]=finite(cap);}
    double flow(int id){return flow[id];}
    double request(int id){return request[id];}
    private static double finite(double v){return Double.isFinite(v)&&v>0?v:0;}
    /** Topological request used to preserve fractional protection grants, before slew capacities. */
    void prepare(){
        Arrays.fill(headOut,0,nodes,-1);Arrays.fill(headIn,0,nodes,-1);
        // Reverse insertion keeps ascending edge order when walking the adjacency lists.
        for(int e=edges-1;e>=0;e--){nextOut[e]=headOut[from[e]];headOut[from[e]]=e;nextIn[e]=headIn[to[e]];headIn[to[e]]=e;}
        for(int root=0;root<nodes;root++){
            if(headOut[root]<0){request[root]=demand[root];continue;}
            Arrays.fill(distance,0,nodes,-1);int start=0,end=0;queue[end++]=root;distance[root]=0;pathEfficiency[root]=1;
            double need=demand[root];
            while(start<end){int v=queue[start++];
                for(int e=headOut[v];e>=0;e=nextOut[e]){
                    int w=to[e];if(capacity[e]<=EPS || efficiency[e]<=EPS || distance[w]>=0)continue;
                    distance[w]=distance[v]+1;pathEfficiency[w]=pathEfficiency[v]*efficiency[e];queue[end++]=w;
                    if(pathEfficiency[w]>EPS)need+=demand[w]/pathEfficiency[w];
                }
            }
            request[root]=finite(need);
        }
    }
    void solve(){
        System.arraycopy(supply,0,available,0,nodes);System.arraycopy(demand,0,unmet,0,nodes);
        System.arraycopy(capacity,0,remaining,0,edges);Arrays.fill(flow,0,edges,0);
        // Each productive pass consumes a source, demand, or edge bottleneck. The extra passes
        // allow local relay loads to become energized before their downstream paths are opened.
        for(int tier=3;tier>=1;tier--){
            boolean eligible=false;for(int e=0;e<edges;e++)if(priority[e]==tier&&capacity[e]>EPS){eligible=true;break;}
            if(!eligible)continue;
            // Finish the current tier before lower tiers consume newly available paths.
            for(int pass=0;pass<nodes+edges+1;pass++)if(allocate(tier)<=EPS)break;
        }
    }
    private void distances(int sink,int tier){
        Arrays.fill(distance,0,nodes,-1);Arrays.fill(nextEdge,0,nodes,-1);int start=0,end=0;
        queue[end++]=sink;distance[sink]=0;pathEfficiency[sink]=1;
        while(start<end){int v=queue[start++];
            // Native PowerGraph shares power locally. Passing power on while this graph's
            // own demand remains uncovered would let the planner report fictitious delivery.
            if(v!=sink && unmet[v]>EPS)continue;
            for(int e=headIn[v];e>=0;e=nextIn[e]){
                int w=from[e];if(priority[e]<tier || remaining[e]<=EPS || efficiency[e]<=EPS || distance[w]>=0)continue;
                distance[w]=distance[v]+1;nextEdge[w]=e;pathEfficiency[w]=efficiency[e]*pathEfficiency[v];queue[end++]=w;
            }
        }
    }
    private boolean firstEdge(int source,int e,int sink,int tier){
        int next=to[e];
        return priority[e]>=tier&&remaining[e]>EPS&&efficiency[e]>EPS&&distance[next]>=0&&distance[next]<distance[source]
            &&(next==sink||unmet[next]<=EPS)&&efficiency[e]*pathEfficiency[next]>EPS;
    }
    private void buildPath(int first,int sink){
        pathLength=0;pathFactor=efficiency[first]*pathEfficiency[to[first]];int edge=first;
        while(edge>=0){path[pathLength++]=edge;int v=to[edge];if(v==sink)break;edge=nextEdge[v];}
    }
    private double allocate(int tier){
        sourceCount=0;sinkCount=0;
        for(int n=0;n<nodes;n++){if(available[n]>EPS)sources[sourceCount++]=n;if(unmet[n]>EPS)sinks[sinkCount++]=n;}
        if(sourceCount==0||sinkCount==0)return 0;
        Arrays.fill(sourceAsk,0,nodes,0);Arrays.fill(edgeAsk,0,edges,0);
        Arrays.fill(usedSource,0,nodes,0);Arrays.fill(usedDemand,0,nodes,0);Arrays.fill(usedEdge,0,edges,0);
        double progress=0;
        // Stream the routes twice rather than retaining source-count × sink-count paths.
        // The first pass sums requests; the second uses those frozen sums. No budgets change
        // until ALL routes have been scaled, so memory is O(graphs + edges), not cubic.
        for(int phase=0;phase<2;phase++)for(int sn=0;sn<sinkCount;sn++){
            int sink=sinks[sn];distances(sink,tier);int count=0;
            for(int si=0;si<sourceCount;si++){int source=sources[si];if(distance[source]<=0)continue;
                for(int e=headOut[source];e>=0;e=nextOut[e])if(firstEdge(source,e,sink,tier))count++;
            }
            if(count==0)continue;
            for(int si=0;si<sourceCount;si++){int source=sources[si];if(distance[source]<=0)continue;
                for(int first=headOut[source];first>=0;first=nextOut[first]){
                    if(!firstEdge(source,first,sink,tier))continue;buildPath(first,sink);if(pathFactor<=EPS)continue;
                    double want=Math.min(unmet[sink]/count,available[source]*pathFactor),factor=1;
                    for(int j=pathLength-1;j>=0;j--){int e=path[j];factor*=efficiency[e];want=Math.min(want,remaining[e]*factor);}
                    if(phase==0){
                        sourceAsk[source]+=want/pathFactor;factor=1;
                        for(int j=pathLength-1;j>=0;j--){int e=path[j];factor*=efficiency[e];edgeAsk[e]+=want/factor;}
                    }else{
                        double scale=Math.min(1,available[source]/Math.max(EPS,sourceAsk[source]));
                        for(int j=0;j<pathLength;j++){int e=path[j];scale=Math.min(scale,remaining[e]/Math.max(EPS,edgeAsk[e]));}
                        double delivered=want*scale;if(delivered<=EPS)continue;progress+=delivered;
                        usedSource[source]+=delivered/pathFactor;usedDemand[sink]+=delivered;factor=1;
                        for(int j=pathLength-1;j>=0;j--){int e=path[j];factor*=efficiency[e];usedEdge[e]+=delivered/factor;}
                    }
                }
            }
        }
        for(int n=0;n<nodes;n++){available[n]=Math.max(0,available[n]-usedSource[n]);unmet[n]=Math.max(0,unmet[n]-usedDemand[n]);}
        for(int e=0;e<edges;e++){remaining[e]=Math.max(0,remaining[e]-usedEdge[e]);flow[e]+=usedEdge[e];}
        return progress;
    }
}
