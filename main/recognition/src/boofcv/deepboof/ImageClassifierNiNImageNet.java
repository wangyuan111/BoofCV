/*
 * Copyright (c) 2011-2016, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.deepboof;

import boofcv.abst.scene.ImageClassifier;
import boofcv.alg.misc.GPixelMath;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.Planar;
import deepboof.Function;
import deepboof.graph.FunctionSequence;
import deepboof.io.torch7.ConvertTorchToBoofForward;
import deepboof.io.torch7.ParseAsciiTorch7;
import deepboof.io.torch7.ParseBinaryTorch7;
import deepboof.io.torch7.SequenceAndParameters;
import deepboof.io.torch7.struct.*;
import deepboof.tensors.Tensor_F32;
import org.ddogleg.struct.FastQueue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static deepboof.misc.TensorOps.WI;

/**
 * TODO comment
 *
 * @author Peter Abeles
 */
// https://gist.github.com/szagoruyko/0f5b4c5e2d2b18472854
// https://github.com/soumith/imagenet-multiGPU.torch/blob/master/models/ninbn.lua
// https://github.com/szagoruyko/torch-opencv-demos
// TODO compare to torch results
public class ImageClassifierNiNImageNet implements ImageClassifier<Planar<GrayF32>> {

	FunctionSequence<Tensor_F32,Function<Tensor_F32>> network;

	// normalization parameters
	float mean[];
	float stdev[];

	// List of all the categories
	List<String> categories = new ArrayList<>();

	ImageType<Planar<GrayF32>> imageType = ImageType.pl(3,GrayF32.class);

	// Resizes input image for the network
	ClipAndReduce<Planar<GrayF32>> massage = new ClipAndReduce<>(true,imageType);

//	int imageSize = 256;
	int imageCrop = 224;

	//  Input image adjusted to network input size
	Planar<GrayF32> imageRgb = new Planar<>(GrayF32.class,imageCrop,imageCrop,3);

	// Input image with the bands in the correct order
	Planar<GrayF32> imageBgr = new Planar<>(GrayF32.class,imageCrop,imageCrop,3);

	// Storage for the tensor into the image
	Tensor_F32 tensorInput = new Tensor_F32(1,3,imageCrop,imageCrop);
	Tensor_F32 tensorOutput;

	// storage for the final output
	FastQueue<Score> categoryScores = new FastQueue<>(Score.class,true);
	int categoryBest;

	@Override
	public void loadModel(File directory) throws IOException {

		List<TorchObject> list = new ParseBinaryTorch7().parse(new File(directory,"nin_bn_final.t7"));
		TorchGeneric torchSequence = ((TorchGeneric)list.get(0)).get("model");

		TorchGeneric torchNorm = torchSequence.get("transform");

		mean = torchListToArray( (TorchList)torchNorm.get("mean"));
		stdev = torchListToArray( (TorchList)torchNorm.get("std"));

		SequenceAndParameters<Tensor_F32, Function<Tensor_F32>> seqparam =
				ConvertTorchToBoofForward.convert(torchSequence);

		network = seqparam.createForward(3,imageCrop,imageCrop);
		tensorOutput = new Tensor_F32(WI(1,network.getOutputShape()));

		TorchList torchCategories = (TorchList)new ParseAsciiTorch7().parse(new File(directory,"synset.t7")).get(0);

		categories.clear();
		for (int i = 0; i < torchCategories.list.size(); i++) {
			categories.add( ((TorchString)torchCategories.list.get(i)).message );
		}
	}

	private float[] torchListToArray( TorchList torch ) {
		float []ret = new float[ torch.list.size()];

		for (int i = 0; i < ret.length; i++) {
			ret[i] = (float)((TorchNumber)torch.list.get(i)).value;
		}

		return ret;
	}

	@Override
	public ImageType<Planar<GrayF32>> getInputType() {
		return imageType;
	}

	/**
	 * The original implementation takes in an image then crops it randomly.  This is primarily for training but is
	 * replicated here to reduce the number of differences
	 *
	 * @param image Image being processed.  Must be RGB image.  Pixel values must have values from 0 to 255.
	 */
	@Override
	public void classify(Planar<GrayF32> image) {
		// Shrink the image to input size
		if( image.width == imageCrop && image.height == imageCrop ) {
			this.imageRgb.setTo(image);
		} else if( image.width < imageCrop || image.height < imageCrop ) {
			throw new IllegalArgumentException("Image width or height is too small");
		} else {
			massage.massage(image,imageRgb);
		}

		// image net is BGR color order
		imageBgr.bands[0] = imageRgb.bands[2];
		imageBgr.bands[1] = imageRgb.bands[1];
		imageBgr.bands[2] = imageRgb.bands[0];

		// image needs to be between 0 and 1
		GPixelMath.divide(imageBgr,255,imageBgr);

		// Normalize the image's statistics
		for (int band = 0; band < 3; band++) {
			DataManipulationOps.normalize(imageBgr.getBand(band),mean[band],stdev[band]);
		}

		// Convert into a tensor and process
		DataManipulationOps.imageToTensor(imageBgr,tensorInput,0);

		network.process(tensorInput,tensorOutput);

		// TODO turn into a function
		// Examine and save results
		categoryScores.reset();
		double scoreBest = -Double.MAX_VALUE;
		categoryBest = -1;
		for (int category = 0; category < tensorOutput.length(1); category++) {
			double score = tensorOutput.get(0,category);
			categoryScores.grow().set(score,category);
			if( score > scoreBest ) {
				scoreBest = score;
				categoryBest = category;
			}
		}
	}

	@Override
	public int getBestResult() {
		return categoryBest;
	}

	@Override
	public List<Score> getAllResults() {
		return categoryScores.toList();
	}

	@Override
	public List<String> getCategories() {
		return categories;
	}
}
