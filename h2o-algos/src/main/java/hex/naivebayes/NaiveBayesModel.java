package hex.naivebayes;

import hex.*;
import hex.genmodel.GenModel;
import hex.schemas.NaiveBayesModelV3;
import water.H2O;
import water.Key;
import water.api.ModelSchema;
import water.util.TwoDimTable;

public class NaiveBayesModel extends SupervisedModel<NaiveBayesModel,NaiveBayesModel.NaiveBayesParameters,NaiveBayesModel.NaiveBayesOutput> {
  public static class NaiveBayesParameters extends SupervisedModel.SupervisedParameters {
    public double _laplace = 0;         // Laplace smoothing parameter
    public double _eps_sdev = 0;   // Cutoff below which standard deviation is replaced with _min_sdev
    public double _min_sdev = 0.001;   // Minimum standard deviation to use for observations without enough data
    public double _eps_prob = 0;   // Cutoff below which probability is replaced with _min_prob
    public double _min_prob = 0.001;   // Minimum conditional probability to use for observations without enough data
    public boolean _compute_metrics = true;   // Should a second pass be made through data to compute metrics?
  }

  public static class NaiveBayesOutput extends SupervisedModel.SupervisedOutput {
    // Class distribution of the response
    public TwoDimTable _apriori;
    public double[/*res level*/] _apriori_raw;

    // For every predictor, a table providing, for each attribute level, the conditional probabilities given the target class
    public TwoDimTable[/*predictor*/] _pcond;
    public double[/*predictor*/][/*res level*/][/*pred level*/] _pcond_raw;

    // Count of response levels
    public int[] _rescnt;

    // Domain of the response
    public String[] _levels;

    // Number of categorical predictors
    public int _ncats;

    public NaiveBayesOutput(NaiveBayes b) { super(b); }
  }

  public NaiveBayesModel(Key selfKey, NaiveBayesParameters parms, NaiveBayesOutput output) { super(selfKey,parms,output); }

  public ModelSchema schema() {
    return new NaiveBayesModelV3();
  }

  // TODO: Constant response shouldn't be regression. Need to override getModelCategory()
  @Override public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    switch(_output.getModelCategory()) {
      case Binomial:    return new ModelMetricsBinomial.MetricBuilderBinomial(domain);
      case Multinomial: return new ModelMetricsMultinomial.MetricBuilderMultinomial(domain.length,domain);
      default: throw H2O.unimpl();
    }
  }

  // Note: For small probabilities, product may end up zero due to underflow error. Can circumvent by taking logs.
  @Override protected double[] score0(double[] data, double[] preds) {
    double[] nums = new double[_output._levels.length];    // log(p(x,y)) for all levels of y
    assert preds.length == (_output._levels.length + 1);   // Note: First column of preds is predicted response class

    // Compute joint probability of predictors for every response class
    for(int rlevel = 0; rlevel < _output._levels.length; rlevel++) {
      // Take logs to avoid overflow: p(x,y) = p(x|y)*p(y) -> log(p(x,y)) = log(p(x|y)) + log(p(y))
      nums[rlevel] = Math.log(_output._apriori_raw[rlevel]);

      for(int col = 0; col < _output._ncats; col++) {
        if(Double.isNaN(data[col])) continue;   // Skip predictor in joint x_1,...,x_m if NA
        int plevel = (int)data[col];
        double prob = plevel < _output._pcond_raw.length ? _output._pcond_raw[col][rlevel][plevel] :
                _parms._laplace / ((double)_output._rescnt[rlevel] + _parms._laplace * _output._domains[col].length);   // Laplace smoothing if predictor level unobserved in training set
        nums[rlevel] += Math.log(prob <= _parms._eps_prob ? _parms._min_prob : prob);   // log(p(x|y)) = \sum_{j = 1}^m p(x_j|y)
      }

      // For numeric predictors, assume Gaussian distribution with sample mean and variance from model
      for(int col = _output._ncats; col < data.length; col++) {
        if(Double.isNaN(data[col])) continue;   // Skip predictor in joint x_1,...,x_m if NA
        double x = data[col];
        double mean = Double.isNaN(_output._pcond_raw[col][rlevel][0]) ? 0 : _output._pcond_raw[col][rlevel][0];
        double stddev = Double.isNaN(_output._pcond_raw[col][rlevel][1]) ? 1.0 :
          (_output._pcond_raw[col][rlevel][1] <= _parms._eps_sdev ? _parms._min_sdev : _output._pcond_raw[col][rlevel][1]);
        // double prob = Math.exp(new NormalDistribution(mean, stddev).density(data[col])); // slower
        double prob = Math.exp(-((x-mean)*(x-mean))/(2.*stddev*stddev)) / (stddev*Math.sqrt(2.*Math.PI)); // faster
        nums[rlevel] += Math.log(prob <= _parms._eps_prob ? _parms._min_prob : prob);
      }
    }

    // Numerically unstable:
    // p(x,y) = exp(log(p(x,y))), p(x) = \Sum_{r = levels of y} exp(log(p(x,y = r))) -> p(y|x) = p(x,y)/p(x)
    // Instead, we rewrite using a more stable form:
    // p(y|x) = p(x,y)/p(x) = exp(log(p(x,y))) / (\Sum_{r = levels of y} exp(log(p(x,y = r)))
    //        = 1 / ( exp(-log(p(x,y))) * \Sum_{r = levels of y} exp(log(p(x,y = r))) )
    //        = 1 / ( \Sum_{r = levels of y} exp( log(p(x,y = r)) - log(p(x,y)) ))
    for(int i = 0; i < nums.length; i++) {
      double sum = 0;
      for(int j = 0; j < nums.length; j++)
        sum += Math.exp(nums[j] - nums[i]);
      preds[i+1] = 1/sum;
    }

    // Select class with highest conditional probability
    preds[0] = GenModel.getPrediction(preds, data, defaultThreshold());
    return preds;
  }
}
